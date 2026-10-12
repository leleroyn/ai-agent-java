package com.jtzj.agent.core.service;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.support.SourceFiles;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 抽取类任务的统一入口：图片 / PDF 先 OCR 换文本，落成任务目录里的同名 {@code .txt}，
 * 再交给 {@link TextService} 按块抽取。
 *
 * <p>为什么统一走 OCR：视觉模型读长数字/字母串不可靠（实测把 18 位统一社会信用代码读丢一位），
 * 而字段抽取要的正是这些值。页型探测 + 视觉/OCR 双通道那套判定链因此整体消失。
 *
 * <p>PDF <b>逐页 OCR、边识别边追加</b>到同一个 txt：一次请求换整份文本容易超时，逐页则每页一次
 * 小请求，中途失败也已留下前面页的文本。页数 &gt; 2 时用 2 个并发 worker，但<b>写入严格按页序</b>
 * （一批内先全部识别完、再按页号顺序落盘）。每页前写 {@code --- 第 N 页 ---} 便于溯源。
 * 图片一次 OCR。
 *
 * <p><b>同一份内容只 OCR 一次</b>：txt 文件名带内容的 SHA-256 前缀，所以同一份文件不管被点名几次、
 * 换个什么路径，第二次调用都直接复用那份 txt。按来源字符串缓存做不到这一点——实测模型为一个字段
 * 把同一来源换个名字传进来，key 变成两个，72 页被重新识别一遍。
 *
 * <p>OCR 文本落在任务目录的 {@code .ocr/} 子目录，<b>不进主模型上下文</b>：工具只返回归并后的字段
 * 值，返回值里不出现这个路径。藏进子目录本身拦不住模型——框架的 list_directory 用 {@code Files.list}
 * （不过滤点开头目录），view_text_file 也不校验后缀，只校验路径在沙箱内。真正的隔离靠部署侧关掉
 * {@code agent.tools.read-file} 并把 shell 白名单收成 curl：模型没有读文件的入口。
 * 同一来源换一批字段再抽时直接复用那份 txt，不重新识别（不做内存缓存，去重只看文件在不在）。
 * 跑完只在 {@code agent.tools.delete-workspace-on-finish=true} 时随整个任务目录一起清掉；
 * 配成 false 时全部保留，便于事后排查。
*/
@Component
public class OcrTextService {

    private static final Logger log = LoggerFactory.getLogger(OcrTextService.class);
    /** OCR 文本与临时页图的去向：仍在任务沙箱内，仅在 delete-workspace-on-finish=true 时随任务目录清除。 */
    public static final String SCRATCH_DIR = ".ocr";

    /**
     * 有页 OCR 失败时产物的名字后缀。完整性判断就写在文件名里：
     * 复用只认没有这个后缀的文件，残缺结果永远不会被当成全文。
     */
    public static final String PARTIAL_SUFFIX = "-partial";
    /** PDF 页数超过该值才开并发（1-2 页串行更省一次握手）。 */
    private static final int PARALLEL_ABOVE_PAGES = 2;

    private final AgentProperties props;
    private final IpsClient ipsClient;
    private final PdfService pdfService;

    public OcrTextService(AgentProperties props, IpsClient ipsClient, PdfService pdfService) {
        this.props = props;
        this.ipsClient = ipsClient;
        this.pdfService = pdfService;
    }

    /**
     * 把一份图片/PDF 转成任务目录里的文本文件。
     *
     * @param source  任务工作目录内的相对路径（URL 已在任务启动前由 SourceIngestor 换成本地路径）
     * @param taskDir 任务沙箱目录
     * @return 成功时返回 txt 在任务目录内的相对路径（{@code .ocr/xxx.txt}）；失败时返回 {@code "Error: ..."}
    */
    public String toTextFile(String source, Path taskDir) {
        if (!ipsClient.isEnabled()) {
            return "Error: OCR 通道未启用（agent.ips.enabled=false 或未配 agent.ips.base-url），抽取无法进行。";
        }
        Path scratch = null;
        try {
            scratch = scratchDir(taskDir);
            SourceFiles.Fetched fetched = SourceFiles.fetch(source, taskDir);
            if (!fetched.ok()) {
                return fetched.error();
            }
            Path file = fetched.file();
            if (Files.size(file) == 0) {
                return "Error: 文件为空。";
            }
            // 文件名带内容哈希：同一份内容不管被点名几次、换个什么路径，都只 OCR 一次。
            String hash = SourceFiles.sha256(file);
            String stem = baseName(source) + "-" + hash.substring(0, 12);
            Path out = scratch.resolve(stem + ".txt");
            Path partialOut = scratch.resolve(stem + PARTIAL_SUFFIX + ".txt");
            if (Files.isRegularFile(out) && Files.size(out) > 0) {
                log.info("reuse txt {} for source {} (same content, sha256={})",
                        out, source, hash.substring(0, 12));
                return SCRATCH_DIR + "/" + out.getFileName();
            }
            // 上一次的残缺结果不复用：宁可重跑一遍 OCR，也不能让一页瞬时失败永久吃掉那几页的字段。
            Files.deleteIfExists(partialOut);
            String error = isPdf(file)
            ? ocrPdfPerPage(file, out, partialOut, source, scratch)
            : ocrWholeFile(file, out, source);
            if (error != null) {
                return error;
            }
            Path produced = Files.exists(out) ? out : partialOut;
            if (!Files.exists(produced)) {
                return "Error: OCR 已完成但没有产出文本。";
            }
            return SCRATCH_DIR + "/" + produced.getFileName();
        } catch (Exception e) {
            log.warn("ocr->txt failed for {}: {}", source, e.toString());
            return "Error: 转文本失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 图片等非 PDF 文件：一次 OCR。 */
    /** @return 失败原因；{@code null} 表示成功 */
    private String ocrWholeFile(Path file, Path out, String source) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        String text = ipsClient.ocr(bytes, file.getFileName().toString());
        if (text.startsWith("Error:")) {
            return text;
        }
        if (text.isBlank()) {
            return "Error: OCR 没有识别出任何文字（可能是纯图形或分辨率过低）。";
        }
        Files.writeString(out, text, StandardCharsets.UTF_8);
        log.info("ocr->txt src={} bytes={} chars={} file={}", source, bytes.length, text.length(), out);
        return null;
    }

    /**
     * PDF：按 {@code agent.pdf.pages-per-call} 分批栅格化，每批内 OCR（页数 &gt; 2 时 2 并发），
     * 一批识别完再按页号顺序追加写入同一个 txt。单页失败写一行显式缺口标记并继续；
     * 全部失败才报错。有失败页时产物改名带 {@link #PARTIAL_SUFFIX}，下次调用不复用、重跑。
    */
    /** @return 失败原因；{@code null} 表示成功 */
    private String ocrPdfPerPage(Path pdf, Path out, Path partialOut, String source, Path scratch)
            throws Exception {
        int total = pdfService.pageCount(pdf);
        int maxPages = props.getPdfs().getMaxPages();
        int limit = Math.min(total, Math.max(1, maxPages));
        int batch = Math.max(1, props.getPdfs().getPagesPerCall());
        int parallelism = props.getPdfs().getOcrParallelism();
        boolean parallel = limit > PARALLEL_ABOVE_PAGES && parallelism > 1;
        List<Integer> failed = new ArrayList<>();
        int ok = 0;
        int chars = 0;
        long started = System.currentTimeMillis();
        ExecutorService exec = parallel ? Executors.newFixedThreadPool(parallelism) : null;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (int a = 1; a <= limit; a += batch) {
                int b = Math.min(limit, a + batch - 1);
                List<byte[]> pages;
                try {
                    pages = pdfService.renderPageImages(pdf, a, b, scratch);
                } catch (Exception e) {
                    log.warn("rasterize pages {}-{} failed: {}", a, b, e.toString());
                    for (int p = a; p <= b; p++) {
                        w.write(missingPage(p));
                        failed.add(p);
                    }
                    continue;
                }
                String[] texts = ocrBatch(pages, a, exec);
                for (int i = 0; i < texts.length; i++) {
                    int page = a + i;
                    String text = texts[i];
                    if (text == null || text.startsWith("Error:")) {
                        log.warn("ocr page {} failed: {}", page, text);
                        w.write(missingPage(page));
                        failed.add(page);
                        continue;
                    }
                    w.write("--- 第 " + page + " 页 ---\n");
                    w.write(text);
                    if (!text.endsWith("\n")) {
                        w.write("\n");
                    }
                    ok++;
                    chars += text.length();
                }
                w.flush();
                log.info("ocr->txt page {}/{} ok={} chars={} parallel={} elapsed={}ms",
                        b, limit, ok, chars, parallel, System.currentTimeMillis() - started);
            }
            if (total > limit) {
                w.write("--- 说明：文档共 " + total + " 页，只识别了前 " + limit + " 页 ---\n");
            }
        } finally {
            if (exec != null) {
                exec.shutdownNow();
            }
            cleanPageImages(scratch);
        }
        if (ok == 0) {
            Files.deleteIfExists(out);
            return "Error: PDF 共 " + limit + " 页，逐页 OCR 全部失败"
            + (failed.isEmpty() ? "" : "（失败页 " + failed + "）") + "。";
        }
        if (!failed.isEmpty()) {
            // 残缺产物换名落盘：复用判断只认完整名，下次调用会重跑，瞬时故障可恢复。
            Files.move(out, partialOut, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("ocr->txt pdf src={} pages={}/{} failed={} chars={} parallel={} file={} elapsed={}ms",
        source, ok, limit, failed, chars, parallel,
        failed.isEmpty() ? out : partialOut, System.currentTimeMillis() - started);
        return null;
    }

    /** 失败页在 txt 里留一行显式缺口，抽取侧和主模型都能看到「这几页没内容」。 */
    private static String missingPage(int page) {
        return "--- 第 " + page + " 页 OCR 失败（该页内容缺失）---\n";
    }

    /**
     * 一批页的 OCR。{@code exec} 为空则串行；否则并发提交、按提交顺序取回，
     * 返回值下标 i 对应第 {@code firstPage + i} 页——并发只发生在识别阶段，顺序由这里保证。
    */
    private String[] ocrBatch(List<byte[]> pages, int firstPage, ExecutorService exec) {
        String[] texts = new String[pages.size()];
        if (exec == null) {
            for (int i = 0; i < pages.size(); i++) {
                texts[i] = ipsClient.ocr(pages.get(i), "page-" + (firstPage + i) + ".jpg");
            }
            return texts;
        }
        List<Future<String>> futures = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            final byte[] image = pages.get(i);
            final String filename = "page-" + (firstPage + i) + ".jpg";
            futures.add(exec.submit(() -> ipsClient.ocr(image, filename)));
        }
        for (int i = 0; i < futures.size(); i++) {
            try {
                texts[i] = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                texts[i] = "Error: OCR 被中断（页 " + (firstPage + i) + "）";
                break;
            } catch (Exception e) {
                texts[i] = "Error: OCR 异常：" + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        return texts;
    }

    /** pdftoppm 中途失败可能留下页图，统一清掉（正常路径 rasterizeBytes 读完即删）。 */
    private static void cleanPageImages(Path dir) {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("ocrpg") && n.toLowerCase().endsWith(".jpg");
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响结果
                }
            });
        } catch (Exception ignored) {
            // 列目录失败不影响结果
        }
    }

    /**
     * OCR 产物目录：{@code <任务目录>/.ocr}。仍在任务沙箱内，只是不与任务产物混在一起；
     * 真正的隔离是抽取用完由调用方删掉这个文件（见 DocumentExtractTools.dropScratch）。
    */
    private static Path scratchDir(Path taskDir) throws Exception {
        Path dir = taskDir.resolve(SCRATCH_DIR);
        Files.createDirectories(dir);
        return dir;
    }

    private static boolean isPdf(Path file) throws Exception {
        byte[] head = new byte[5];
        try (InputStream in = Files.newInputStream(file)) {
            int n = in.read(head);
            if (n < 5) {
                return false;
            }
        }
        return head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-';
    }

    /** URL 末段或本地文件名去掉扩展名，作为 txt 名；非法字符换成下划线。 */
    private static String baseName(String source) {
        String s = source.trim();
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        s = s.replaceAll("[^0-9A-Za-z._-]", "_");
        return s.isBlank() ? "document" : s;
    }

}

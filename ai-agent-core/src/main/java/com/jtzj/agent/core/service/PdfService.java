package com.jtzj.agent.core.service;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.support.Extraction;
import com.jtzj.agent.core.support.QaMerge;
import com.jtzj.agent.core.support.SandboxPaths;
import com.jtzj.agent.core.support.SourceFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * PDF 服务端能力，只支撑 {@code understand_document}：把页栅格化后连同问题交给视觉模型，
 * 返回自然语言（带绝对页码）。用于自由问答、总结、看图理解。
 *
 * <p>字段抽取不在这里：{@code extract_document_fields} 对图片和 PDF 统一走
 * 「OCR 换文本（PDF 逐页识别、按页序追加）→ 同名 .txt → 按块抽取」（见 {@link OcrTextService}
 * 与 {@link TextService}），因为视觉模型读长数字串不可靠（实测把 18 位统一社会信用代码读丢一位），
 * 而 OCR 文本稳定。
 *
 * <p>页面图像只发到 {@code agent.vision} 端点，主模型只拿文字。栅格化用 {@code pdftoppm}
 * （poppler-utils），产物写任务沙箱、用完即删；本地来源的源文件绝不删。
 */
@Component
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);
    private static final Pattern PAGES = Pattern.compile("^Pages:\\s*(\\d+)", Pattern.MULTILINE);
    private static final Pattern TRAIL_NUM = Pattern.compile("-(\\d+)\\.[A-Za-z0-9]+$");

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final VisionClient visionClient;

    public PdfService(AgentProperties props,
                      @Qualifier("agentScopeObjectMapper") ObjectMapper mapper,
                      VisionClient visionClient) {
        this.props = props;
        this.mapper = mapper;
        this.visionClient = visionClient;
    }

    /**
     * 自由问答（{@code understand_document}）。支持 PDF、TXT、图片。
     */
    public String understand(String pdfSource, String question, String pageRange, Path taskDir) {
        AgentProperties.Pdfs cfg = props.getPdfs();
        if (!props.getVision().isEnabled()) {
            return "Error: 视觉功能未启用（agent.vision.enabled=false），无法理解 PDF。";
        }
        if (isHttpUrl(pdfSource)) {
            return "Error: " + SourceFiles.URL_REJECTED;
        }
        Path pdf = null;
        try {
            pdf = resolveSource(pdfSource, taskDir);
            if (pdf == null) {
                return "Error: 本地文档路径必须在任务工作目录内且存在：" + pdfSource
                        + "（指令里点名的文件应已就绪在 src/ 下）";
            }
            int total = pageCount(pdf);
            List<int[]> segments = resolveRanges(pageRange, total);
            int pages = pagesOf(segments).size();
            int cap = effectiveBatch(cfg);
            List<int[]> batches = batchSegments(segments, cap);
            log.info("pdf understand src={} total={} pages={} batches={} cap={}",
                    pdfSource, total, pages, batches.size(), cap);

            String q = (question == null || question.isBlank())
                    ? "请逐页转录并说明每一页的文字与主要内容。" : question.trim();
            List<String> parts = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            int parallel = Math.min(Math.max(1, cfg.getUnderstandParallelism()), batches.size());
            String[] answers = runBatches(pdf, batches, total, q, taskDir, parallel);
            for (int bi = 0; bi < batches.size(); bi++) {
                int[] seg = batches.get(bi);
                String out = answers[bi];
                if (out == null) {
                    failed.add(seg[0] + "-" + seg[1]);
                    continue;
                }
                parts.add("【第 " + seg[0] + "-" + seg[1] + " 页】\n" + out);
            }
            if (parts.isEmpty()) {
                return "Error: 这份 PDF 的 " + pages + " 页全部没能理解（栅格化或视觉模型失败）。";
            }
            String merged = QaMerge.merge(visionClient, parts, q);
            if (!failed.isEmpty()) {
                merged += "\n\n（以下页未能理解，结论不覆盖它们：" + String.join("、", failed) + "）";
            }
            log.info("pdf understand done src={} pages={} batches={} ok={} failed={} answerChars={}",
                    pdfSource, pages, batches.size(), parts.size(), failed.size(), merged.length());
            return merged;
        } catch (Exception e) {
            log.error("pdf understand failed src={}", pdfSource, e);
            return "Error: 处理 PDF 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            cleanup(taskDir, null);
        }
    }

    // ---- 单批结构化抽取 ----

    /** 自由问答提示（understand_document）。 */
    private String buildQaPrompt(List<Integer> pages, int total, String question) {
        StringBuilder map = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                map.append("，");
            }
            map.append("第 ").append(i + 1).append(" 张图=第 ").append(pages.get(i)).append(" 页");
        }
        return "这是同一份文档的一部分（全文共 " + total + " 页）。\n"
                + "按顺序：" + map + "。\n"
                + "只根据这些图片回答问题，不要编造；结论涉及具体文档内容时，标注它所在的页码。\n要求："
                + question;
    }

    // ---- 含图片页探测 / OCR 文本通道（PDF 提速） ----

    // ---- 结果构造 / 解析 ----

    // ---- 来源解析 / 栅格化 / 基础设施（两工具共用） ----

    /** 本地→沙箱内校验。返回文件；越界或不存在返回 null。URL 已在入口拒绝。 */
    private Path resolveSource(String pdfSource, Path taskDir) {
        Path local = SandboxPaths.resolveWithin(taskDir, pdfSource);
        if (local == null || !Files.isRegularFile(local)) {
            return null;
        }
        return local;
    }

    /** 每批喂视觉模型的页数 = min(pdf.pages-per-call, vision.max-images)，至少 1。 */
    /**
     * 分批问视觉模型。批与批并发（{@code agent.pdfs.understand-parallelism}），但返回值按批序放回，
     * 后面的归并因此与串行时完全一致；失败批返回 {@code null}，由调用方按序归入未覆盖页。
     */
    private String[] runBatches(Path pdf, List<int[]> batches, int total, String q, Path taskDir, int parallel) {
        String[] answers = new String[batches.size()];
        if (parallel <= 1) {
            for (int bi = 0; bi < batches.size(); bi++) {
                answers[bi] = askBatch(pdf, batches.get(bi), bi + 1, batches.size(), total, q, taskDir);
            }
            return answers;
        }
        ExecutorService exec = Executors.newFixedThreadPool(parallel);
        try {
            List<Future<?>> futures = new ArrayList<>(batches.size());
            for (int bi = 0; bi < batches.size(); bi++) {
                final int idx = bi;
                futures.add(exec.submit(() ->
                        answers[idx] = askBatch(pdf, batches.get(idx), idx + 1, batches.size(), total, q, taskDir)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("pdf understand batch task failed: {}", e.toString());
                }
            }
        } finally {
            exec.shutdownNow();
        }
        return answers;
    }

    /** 单批：栅格化 → 问视觉模型 → 删掉本批页图。失败返回 {@code null}。 */
    private String askBatch(Path pdf, int[] seg, int batchNo, int batchCount, int total, String q, Path taskDir) {
        try {
            List<String> urls = rasterize(pdf, seg[0], seg[1], batchNo, taskDir);
            if (urls.size() != seg[1] - seg[0] + 1) {
                log.warn("pdf understand batch {}/{} pages={}-{} rasterized {} of {}",
                        batchNo, batchCount, seg[0], seg[1], urls.size(), seg[1] - seg[0] + 1);
                return null;
            }
            String out = visionClient.extractFromDataUrls(urls, buildQaPrompt(pagesOf(seg), total, q));
            if (out.startsWith("Error:")) {
                log.warn("pdf understand batch {}/{} pages={}-{} failed: {}", batchNo, batchCount, seg[0], seg[1], out);
                return null;
            }
            return out.trim();
        } catch (Exception e) {
            log.warn("pdf understand batch {}/{} pages={}-{} error: {}",
                    batchNo, batchCount, seg[0], seg[1], e.toString());
            return null;
        } finally {
            // 只删本批的页图（文件名前缀带批号），并发下不会碰到别的批。
            cleanup(taskDir, null, "pg" + batchNo + "-");
        }
    }

    private int effectiveBatch(AgentProperties.Pdfs cfg) {
        int k = Math.min(cfg.getPagesPerCall(), props.getVision().getMaxImages());
        return Math.max(1, k);
    }

    /** 视觉通道栅格化：第 a-b 页 → JPEG data URL 列表。 */
    private List<String> rasterize(Path pdf, int a, int b, int batchId, Path taskDir) throws Exception {
        AgentProperties.Pdfs cfg = props.getPdfs();
        List<byte[]> images = rasterizeBytes(pdf, a, b, "pg" + batchId, taskDir,
                0, cfg.getMaxImageDimension(), cfg.getJpegQuality());
        List<String> urls = new ArrayList<>(images.size());
        for (byte[] img : images) {
            urls.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(img));
        }
        return urls;
    }

    /**
     * OCR 通道用：把第 a-b 页栅格化成 JPEG 字节，按页码顺序返回。
     * dpi/质量取 {@code agent.ips} 配置（OCR 需要比视觉更高的 dpi）。
     */
    public List<byte[]> renderPageImages(Path pdf, int a, int b, Path taskDir) throws Exception {
        AgentProperties.Ips ocr = props.getIps();
        return rasterizeBytes(pdf, a, b, "ocrpg", taskDir,
                ocr.getRasterizeDpi(), ocr.getMaxImageDimension(), ocr.getJpegQuality());
    }

    /**
     * 用 {@code pdftoppm} 把第 a-b 页栅格化成 JPEG 字节（按页码排序，临时文件读完即删）。
     * {@code dpi>0} 下发 {@code -r}（OCR 需要比视觉更高的 dpi）；{@code maxDim>0} 下发
     * {@code -scale-to}（控体积与 token）。
     */
    private List<byte[]> rasterizeBytes(Path pdf, int a, int b, String stem, Path taskDir,
                                        int dpi, int maxDim, int jpegQuality) throws Exception {
        String prefix = taskDir.resolve(stem).toString();
        List<String> cmd = new ArrayList<>(List.of("pdftoppm"));
        if (dpi > 0) {
            cmd.add("-r");
            cmd.add(String.valueOf(dpi));
        }
        if (maxDim > 0) {
            cmd.add("-scale-to");
            cmd.add(String.valueOf(maxDim));
        }
        cmd.add("-jpeg");
        cmd.add("-jpegopt");
        cmd.add("quality=" + jpegQuality);
        cmd.add("-f");
        cmd.add(String.valueOf(a));
        cmd.add("-l");
        cmd.add(String.valueOf(b));
        cmd.add(pdf.toString());
        cmd.add(prefix);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out;
        try (InputStream is = proc.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroy();
            throw new IllegalStateException("pdftoppm 超时 pages=" + a + "-" + b);
        }
        if (proc.exitValue() != 0) {
            throw new IllegalStateException("pdftoppm 退出码 " + proc.exitValue() + ": " + snippet(out));
        }
        final String fileStem = stem + "-";
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(taskDir)) {
            stream.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.startsWith(fileStem) && n.toLowerCase().endsWith(".jpg")) {
                    files.add(p);
                }
            });
        }
        files.sort(Comparator.comparingInt(PdfService::trailingNumber));
        List<byte[]> images = new ArrayList<>(files.size());
        for (Path f : files) {
            images.add(Files.readAllBytes(f));
            Files.deleteIfExists(f);
        }
        return images;
    }

    private static int trailingNumber(Path p) {
        Matcher m = TRAIL_NUM.matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    public int pageCount(Path pdf) throws Exception {
        Process proc = new ProcessBuilder("pdfinfo", pdf.toString())
                .redirectErrorStream(true).start();
        String out;
        try (InputStream is = proc.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroy();
            throw new IllegalStateException("pdfinfo 超时");
        }
        Matcher m = PAGES.matcher(out);
        if (!m.find()) {
            throw new IllegalStateException("pdfinfo 未给出页数（可能不是有效 PDF）：" + snippet(out));
        }
        return Integer.parseInt(m.group(1));
    }

    /**
     * 解析 page_range，支持多段：{@code 1-3}、{@code 5}、{@code 70-}、{@code -3}，段之间用
     * 空格 / 逗号 / 分号 / 顿号分隔。模型会写 {@code "1-5 70-72"} 想要首尾两批；之前只按单个
     * a-b 解析，{@code Integer.parseInt("5 70-72")} 直接抛 NumberFormatException，白烧一轮调用。
     * 重叠或相邻的段会合并，避免同一页栅格化两次。
     */
    /** 把页段按单次上限切成连续小段：栅格化按 -f/-l 取页，段内必须页码连续。 */
    private static List<int[]> batchSegments(List<int[]> segments, int cap) {
        List<int[]> out = new ArrayList<>();
        for (int[] seg : segments) {
            for (int a = seg[0]; a <= seg[1]; a += cap) {
                out.add(new int[]{a, Math.min(seg[1], a + cap - 1)});
            }
        }
        return out;
    }

    private static List<int[]> resolveRanges(String pageRange, int total) {
        if (pageRange == null || pageRange.isBlank()) {
            return List.of(new int[]{1, total});
        }
        String s = pageRange.trim()
                .replace('－', '-').replace('—', '-').replace('~', '-');
        List<int[]> out = new ArrayList<>();
        for (String token : s.split("[\\s,;，、；]+")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            int dash = t.indexOf('-');
            int start;
            int end;
            if (dash < 0) {
                start = parsePage(t, pageRange);
                end = start;
            } else {
                String l = t.substring(0, dash).trim();
                String r = t.substring(dash + 1).trim();
                start = l.isEmpty() ? 1 : parsePage(l, pageRange);
                end = r.isEmpty() ? total : parsePage(r, pageRange);
            }
            start = Math.max(1, start);
            end = Math.min(end, total);
            if (start <= end) {
                out.add(new int[]{start, end});
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(
                    "页范围无效：" + pageRange + "（总页数 " + total + "）");
        }
        out.sort(Comparator.comparingInt(a -> a[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] seg : out) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && seg[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], seg[1]);
            } else {
                merged.add(seg);
            }
        }
        return merged;
    }

    private static int parsePage(String token, String pageRange) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("页范围无效：" + pageRange
                    + "。单个范围写成 \"a-b\"、\"a\"、\"a-\"；多段用空格或逗号分隔，例如 \"1-3 70-72\"。");
        }
    }

    /** 段集合展开成页号列表。 */
    private static List<Integer> pagesOf(int[] seg) {
        return pagesOf(List.of(seg));
    }

    private static List<Integer> pagesOf(List<int[]> segments) {
        List<Integer> pages = new ArrayList<>();
        for (int[] seg : segments) {
            for (int i = seg[0]; i <= seg[1]; i++) {
                pages.add(i);
            }
        }
        return pages;
    }

    /** 段集合的人类可读形式，如 "1-5,70-72"。 */
    private static String describe(List<int[]> segments) {
        StringBuilder sb = new StringBuilder();
        for (int[] seg : segments) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(seg[0] == seg[1] ? String.valueOf(seg[0]) : seg[0] + "-" + seg[1]);
        }
        return sb.toString();
    }

    private static boolean isHttpUrl(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim().toLowerCase();
        return t.startsWith("http://") || t.startsWith("https://");
    }

    // parseFields 统一用 Extraction.parseFields()

    /** 清理临时页图；{@code alsoDelete} 非空则一并删该下载来的源文件（本地来源传 null 不删源文件）。 */
    private void cleanup(Path taskDir, String alsoDelete) {
        cleanup(taskDir, alsoDelete, "pg");
    }

    /** {@code prefix} 决定删哪批页图：{@code pg} 全删，{@code pg3-} 只删第 3 批（带连字符，避免 pg1 误删 pg12）。 */
    private void cleanup(Path taskDir, String alsoDelete, String prefix) {
        try {
            if (taskDir != null && Files.isDirectory(taskDir)) {
                try (var stream = Files.list(taskDir)) {
                    stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefix) && n.toLowerCase().endsWith(".jpg");
                    }).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // 尽力而为
                        }
                    });
                }
                if (alsoDelete != null) {
                    Files.deleteIfExists(taskDir.resolve(alsoDelete));
                }
            }
        } catch (Exception ignored) {
            // 清理失败不影响结果
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

}

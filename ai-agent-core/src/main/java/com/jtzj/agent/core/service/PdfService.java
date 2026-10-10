package com.jtzj.agent.core.service;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.support.Extraction;
import com.jtzj.agent.core.support.SandboxPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * PDF 服务端能力，支撑两个职责单一的工具：
 *
 * <ul>
 *   <li>{@link #understand} —— {@code understand_document}：把一批页栅格化后连同自由问题交给视觉模型，
 *       返回自然语言（带绝对页码）。用于对某几页/小文档做自由问答、总结。</li>
 *   <li>{@link #extractFields} —— {@code extract_document_fields}：服务端<b>遍历整篇</b>，分批并发地按
 *       {@code fields} 结构化抽取，再<b>确定性归并</b>（同字段同值并页码、去重），返回
 *       {@code results:[{field,value,pages}]} + {@code pages_scanned} + {@code incomplete}。
 *       召回由服务端遍历兜底、不依赖主模型分页；语义总结/取舍交给主模型。</li>
 * </ul>
 *
 * <p>与 {@link VisionClient} 同源解耦：页面图像只发到 {@code agent.vision} 端点，主模型只拿文字。
 * 栅格化用 {@code pdftoppm}（poppler-utils），产物写任务沙箱、用完即删；本地来源的源文件绝不删。
 *
 * <p>需要 OCR 的页逐批喂视觉模型很慢，所以先探测：{@code pdfimages -list} 找含图片的页，再加一条兜底——
 * {@code pdftotext} 文字层里中日韩占比过低的页（缺 ToUnicode 的「假文字层」，中文会全丢）。这些页走
 * {@link OcrClient}（逐页 OCR 换文本），其余页取文字层，之后的字段抽取只发文本；
 * <b>只有字段抽取走这条通道</b>：{@code understand_document} 的自由问答始终走视觉，因为 OCR 文本会丢掉版面与图形信息。
 * OCR 未启用或不可用时自动回退视觉通道。
 */
@Component
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);
    private static final Pattern PAGES = Pattern.compile("^Pages:\\s*(\\d+)", Pattern.MULTILINE);
    private static final Pattern TRAIL_NUM = Pattern.compile("-(\\d+)\\.[A-Za-z0-9]+$");
    /** 文字层里中日韩占比低于此值就视为「文字层不可用」，该页改走 OCR（缺 ToUnicode 的 PDF 中文会全丢）。 */
    private static final double MIN_CJK_RATIO = 0.05;

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final VisionClient visionClient;
    private final OcrClient ocrClient;
    private final HttpClient http;

    public PdfService(AgentProperties props,
                      @Qualifier("agentScopeObjectMapper") ObjectMapper mapper,
                      VisionClient visionClient,
                      OcrClient ocrClient) {
        this.props = props;
        this.mapper = mapper;
        this.visionClient = visionClient;
        this.ocrClient = ocrClient;
        this.http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 自由问答（{@code understand_document}）。支持 PDF、TXT、图片。
     */
    public String understand(String pdfSource, String question, String pageRange, Path taskDir) {
        AgentProperties.Pdfs cfg = props.getPdfs();
        if (!props.getVision().isEnabled()) {
            return "Error: 视觉功能未启用（agent.vision.enabled=false），无法理解 PDF。";
        }
        Path pdf = null;
        boolean downloaded = false;
        try {
            pdf = resolveSource(pdfSource, cfg, taskDir);
            if (pdf == null) {
                return "Error: 本地文档路径必须在任务工作目录内且存在：" + pdfSource;
            }
            downloaded = isDownloaded(pdfSource);
            int total = pageCount(pdf);
            int[] range = resolveRange(pageRange, total);
            int start = range[0];
            int end = range[1];
            int pages = end - start + 1;
            int cap = effectiveBatch(cfg);
            log.info("pdf understand src={} total={} range={}-{} pages={} cap={}",
                    pdfSource, total, start, end, pages, cap);
            if (pages > cap) {
                int nextEnd = start + cap - 1;
                return "本文档共 " + total + " 页，本次请求第 " + start + "-" + end + " 页（" + pages
                        + " 页）超过单次上限 " + cap + " 页。若要抽取关键信息请改用 extract_document_fields"
                        + "（它会服务端遍历整篇）；若只是问答，请用 page_range 分批，例如先 \""
                        + start + "-" + nextEnd + "\"、再往后，然后自行汇总。";
            }
            String q = (question == null || question.isBlank())
                    ? "请逐页转录并说明这一页的标题与主要内容。" : question.trim();
            List<String> urls = rasterize(pdf, start, end, 0, taskDir);
            if (urls.isEmpty()) {
                return "Error: 栅格化第 " + start + "-" + end + " 页失败（可能不是有效 PDF）。";
            }
            return visionClient.extractFromDataUrls(urls, buildQaPrompt(start, end, total, q));
        } catch (Exception e) {
            log.error("pdf understand failed src={}", pdfSource, e);
            return "Error: 处理 PDF 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            cleanup(taskDir, downloaded ? "source.pdf" : null);
        }
    }

    /**
     * 结构化抽取（{@code extract_document_fields}）：服务端遍历整篇，分批并发抽取 {@code fields}，
     * 确定性归并后返回紧凑 JSON。召回优先——每个 requested 字段都会出现在 results 里
     * （抽不到 {@code value=null}）。不做语义总结。
     *
     * @return 形如 {@code {"status","total_pages","pages_scanned":[s,e],"incomplete",
     *         "results":[{"field","value","pages":[..]}],"failed_pages":[..]}} 的 JSON 文本
     */
    public String extractFields(String pdfSource, String fieldsRaw, String pageRange, Path taskDir) {
        AgentProperties.Pdfs cfg = props.getPdfs();
        if (!props.getVision().isEnabled()) {
            return "Error: 视觉功能未启用（agent.vision.enabled=false），无法理解 PDF。";
        }
        List<String> fields = Extraction.parseFields(fieldsRaw);
        if (fields.isEmpty()) {
            return "Error: fields 为空，请用 fields 指定要抽取的关键信息（多个用 ; 逗号 、 或换行分隔）。";
        }
        Path pdf = null;
        boolean downloaded = false;
        try {
            pdf = resolveSource(pdfSource, cfg, taskDir);
            if (pdf == null) {
                return "Error: 本地文档路径必须在任务工作目录内且存在：" + pdfSource;
            }
            downloaded = isDownloaded(pdfSource);
            int total = pageCount(pdf);
            int[] range = resolveRange(pageRange, total);
            int start = range[0];
            int end = range[1];
            int scanEnd = Math.min(end, start + cfg.getMaxPages() - 1);
            boolean incomplete = scanEnd < end;
            int k = effectiveBatch(cfg);
            int pool = cfg.getConcurrency();
            log.info("pdf extract src={} total={} range={}-{} scanEnd={} incomplete={} k={} pool={} fields={}",
                    pdfSource, total, start, end, scanEnd, incomplete, k, pool, fields.size());

            // 需要 OCR 的页（含图片的页，或文字层几乎没有中日韩文字的页）走 OCR 换文本，比把这些页喂
            // 视觉模型快得多；其余页直接取 pdftotext 文字层，不花 OCR 成本。
            // 没有页需要 OCR、或 OCR 全部失败时返回 null，自动回退下面的视觉管线。
            if (ocrClient.isEnabled()) {
                PageTexts pt = collectPageTexts(pdf, start, scanEnd, taskDir, false);
                if (pt != null) {
                    LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> ocrAcc = new LinkedHashMap<>();
                    TreeSet<Integer> ocrFailed = new TreeSet<>();
                    extractFromPageTexts(pt, fields, total, start, scanEnd, ocrAcc, ocrFailed);
                    if (!allFieldsEmpty(fields, ocrAcc)) {
                        return buildExtractResult(fields, ocrAcc, ocrFailed, total, start, scanEnd, incomplete, "ocr");
                    }
                    log.info("OCR 通道字段全空，回退视觉管线重跑");
                }
            }

            LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc = new LinkedHashMap<>();
            TreeSet<Integer> failedPages = new TreeSet<>();
            ExecutorService exec = Executors.newFixedThreadPool(pool);
            List<Future<BatchOut>> futures = new ArrayList<>();
            List<int[]> ranges = new ArrayList<>();
            final Path pdfFile = pdf;
            int batchId = 0;
            try {
                for (int a = start; a <= scanEnd; a += k) {
                    int b = Math.min(a + k - 1, scanEnd);
                    final int fa = a;
                    final int fb = b;
                    final int bid = batchId++;
                    ranges.add(new int[]{fa, fb});
                    futures.add(exec.submit(() -> runExtractBatch(pdfFile, fa, fb, bid, fields, total, taskDir)));
                }
                for (int i = 0; i < futures.size(); i++) {
                    int[] rr = ranges.get(i);
                    BatchOut out;
                    try {
                        out = futures.get(i).get();
                    } catch (Exception e) {
                        log.warn("extract batch {}-{} failed: {}", rr[0], rr[1], e.toString());
                        markFailed(failedPages, rr);
                        continue;
                    }
                    if (out.error) {
                        markFailed(failedPages, rr);
                        continue;
                    }
                    for (Extraction.Hit h : out.hits) {
                        if (h.value() == null || h.value().isBlank()) {
                            continue;
                        }
                        acc.computeIfAbsent(h.field(), x -> new LinkedHashMap<>())
                                .computeIfAbsent(h.value().trim(), x -> new TreeSet<>())
                                .addAll(h.pages());
                    }
                }
            } finally {
                exec.shutdownNow();
            }
            // 兜底：视觉管线一个请求字段都没抽到，强制逐页 OCR 再试一次（只在失败时多花这一趟）。
            if (allFieldsEmpty(fields, acc) && ocrClient.isEnabled()) {
                log.info("视觉管线字段全空，强制 OCR 重跑 pages={}-{}", start, scanEnd);
                PageTexts pt = collectPageTexts(pdf, start, scanEnd, taskDir, true);
                if (pt != null) {
                    LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> retryAcc = new LinkedHashMap<>();
                    TreeSet<Integer> retryFailed = new TreeSet<>();
                    extractFromPageTexts(pt, fields, total, start, scanEnd, retryAcc, retryFailed);
                    if (!allFieldsEmpty(fields, retryAcc)) {
                        return buildExtractResult(fields, retryAcc, retryFailed, total, start, scanEnd, incomplete, "ocr");
                    }
                }
            }
            return buildExtractResult(fields, acc, failedPages, total, start, scanEnd, incomplete, "vision");
        } catch (Exception e) {
            log.error("pdf extract failed src={}", pdfSource, e);
            return "Error: 处理 PDF 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            cleanup(taskDir, downloaded ? "source.pdf" : null);
        }
    }

    /** 请求的字段是否一个都没抽到值——「换一条通道再试一次」的兜底判定。 */
    private static boolean allFieldsEmpty(List<String> fields,
                                          LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc) {
        for (String f : fields) {
            LinkedHashMap<String, TreeSet<Integer>> byValue = acc.get(f);
            if (byValue != null && !byValue.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ---- 单批结构化抽取 ----

    private BatchOut runExtractBatch(Path pdf, int a, int b, int batchId,
                                     List<String> fields, int total, Path taskDir) {
        List<String> dataUrls;
        try {
            dataUrls = rasterize(pdf, a, b, batchId, taskDir);
        } catch (Exception e) {
            log.warn("rasterize batch {}-{} failed: {}", a, b, e.toString());
            return BatchOut.failed();
        }
        if (dataUrls.isEmpty()) {
            return BatchOut.failed();
        }
        String answer = visionClient.extractFromDataUrls(dataUrls, buildExtractPrompt(a, b, total, fields));
        if (answer.startsWith("Error:")) {
            log.warn("VL extract batch {}-{} error: {}", a, b, snippet(answer));
            return BatchOut.failed();
        }
        List<Extraction.Hit> hits = Extraction.parseHits(mapper, answer);
        if (hits == null) {
            log.warn("VL extract batch {}-{} answer not parseable", a, b);
            return BatchOut.failed();
        }
        return BatchOut.ok(hits);
    }

    /** 结构化抽取提示：标注绝对页码，要求每字段给值+页码，只输出 JSON。 */
    private String buildExtractPrompt(int a, int b, int total, List<String> fields) {
        return "这是同一份文档的第 " + a + " 到第 " + b + " 页（全文共 " + total + " 页中这一段）。"
                + "按顺序第 1 张图=第 " + a + " 页，第 2 张=第 " + (a + 1) + " 页，依此类推。\n"
                + "只依据这些图片内容，为下面每个字段找出它的值以及出现的绝对页码（用上面标好的页码）。"
                + "同一字段可能多页出现，都列出页码；同一页多个不同值也分开列。图中找不到该字段则不要输出它（不要编造）。\n"
                + "只输出一个 JSON 对象，不要解释、不要代码围栏。需要抽取的字段：\n"
                + String.join("\n", fields) + "\n\n输出格式（严格）：\n"
                + "{\"hits\":[{\"field\":\"字段名\",\"value\":\"值\",\"pages\":[页码数字]}]}";
    }

    /** 自由问答提示（understand_document）。 */
    private String buildQaPrompt(int a, int b, int total, String question) {
        return "这是同一份文档的第 " + a + " 到第 " + b + " 页（全文共 " + total + " 页中这一段）。"
                + "按顺序第 1 张图=第 " + a + " 页，第 2 张图=第 " + (a + 1) + " 页，依此类推。\n"
                + "只依据这些图片内容作答，不要编造；凡是引用了文档中的具体内容，请标注它出现的绝对页码。\n要求："
                + question;
    }

    // ---- 含图片页探测 / OCR 文本通道（PDF 提速） ----

    /**
     * 每页文字层文本（页码→文本）：用 {@code pdftotext} 一次取整段，按换页符切页。
     * 取不到或页数偏多就返回空 map；末尾缺的页按空白页处理（无文本，但不算失败页）。
     */
    private TreeMap<Integer, String> pageTexts(Path pdf, int start, int end) {
        TreeMap<Integer, String> out = new TreeMap<>();
        try {
            Process proc = new ProcessBuilder("pdftotext",
                    "-f", String.valueOf(start), "-l", String.valueOf(end),
                    "-enc", "UTF-8", pdf.toString(), "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String raw;
            try (InputStream is = proc.getInputStream()) {
                raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroy();
                log.warn("pdftotext 超时 pages={}-{}", start, end);
                return out;
            }
            if (proc.exitValue() != 0) {
                log.warn("pdftotext 退出码 {} pages={}-{}", proc.exitValue(), start, end);
                return out;
            }
            String[] parts = raw.split("\f", -1);
            int n = parts.length;
            while (n > 0 && parts[n - 1].isEmpty()) {
                n--;
            }
            int want = end - start + 1;
            if (n > want) {
                log.warn("pdftotext 页数偏多 got={} want={}，该段文字层不可用", n, want);
                return out;
            }
            // 末尾空白页在输出里没有内容：少几页按空白页处理，前面各页页码不受影响
            for (int i = 0; i < n; i++) {
                out.put(start + i, parts[i]);
            }
        } catch (Exception e) {
            log.warn("pdftotext 失败 pages={}-{}: {}", start, end, e.toString());
        }
        return out;
    }

    /**
     * 范围内「含图片」的页码：{@code pdfimages -list} 列出每页的图像 XObject，出现过的页即含图片
     * （扫描件每页基本就是一整张位图）。列不出来返回空集，调用方走视觉管线。
     */
    private TreeSet<Integer> pagesWithImages(Path pdf, int start, int end) {
        TreeSet<Integer> pages = new TreeSet<>();
        try {
            Process proc = new ProcessBuilder("pdfimages", "-list", pdf.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String raw;
            try (InputStream is = proc.getInputStream()) {
                raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroy();
                log.warn("pdfimages 超时 pages={}-{}", start, end);
                return pages;
            }
            if (proc.exitValue() != 0) {
                log.warn("pdfimages 退出码 {} pages={}-{}", proc.exitValue(), start, end);
                return pages;
            }
            for (String line : raw.split("\r?\n")) {
                String s = line.trim();
                if (s.isEmpty() || !Character.isDigit(s.charAt(0))) {
                    continue; // 表头与 ----- 分隔行
                }
                try {
                    int p = Integer.parseInt(s.split("\\s+")[0]);
                    if (p >= start && p <= end) {
                        pages.add(p);
                    }
                } catch (NumberFormatException ignored) {
                    // 非数据行
                }
            }
        } catch (Exception e) {
            log.warn("pdfimages 失败 pages={}-{}: {}", start, end, e.toString());
        }
        return pages;
    }

    /**
     * 组装「页码→文本」：需要 OCR 的页栅格化后并发送 OCR 服务，其余页直接用 pdftotext 的文字层（零成本）。
     *
     * <p>需要 OCR 的页 = 含图片 XObject 的页，<b>或</b>文字层里几乎没有中日韩文字的页。后半条是必须的：
     * 有些 PDF 有文字层但缺 ToUnicode 映射，{@code pdftotext} 取出来的是「非空但完全不可用」的乱码
     * （中文全丢），只按「有没有图片 / 有没有文字」判断会把这种页当成可直接用文字层，字段就抽不出来。
     *
     * @return 页文本与失败页；{@code null} 表示没有任何页需要 OCR、或 OCR 全部失败——调用方走视觉管线
     */
    private PageTexts collectPageTexts(Path pdf, int start, int end, Path taskDir, boolean forceOcr) {
        AgentProperties.Ocr o = props.getOcr();
        TreeMap<Integer, String> texts = pageTexts(pdf, start, end);
        TreeSet<Integer> imagePages = pagesWithImages(pdf, start, end);
        TreeSet<Integer> targetSet = new TreeSet<>(imagePages);
        int unusableTextLayer = 0;
        for (int p = start; p <= end; p++) {
            if (!targetSet.contains(p) && cjkRatio(texts.get(p)) < MIN_CJK_RATIO) {
                targetSet.add(p);
                unusableTextLayer++;
            }
        }
        if (targetSet.isEmpty()) {
            if (!forceOcr) {
                return null;
            }
            // 兜底重跑：视觉管线整篇没抽到任何请求字段，强制逐页 OCR 再试一次。
            for (int p = start; p <= end; p++) {
                targetSet.add(p);
            }
        }
        log.info("ocr 选页 pages={}-{} imagePages={} textLayerUnusable={} ocrPages={}",
                start, end, imagePages.size(), unusableTextLayer, targetSet.size());
        List<Integer> targets = new ArrayList<>(targetSet);
        LinkedHashMap<Integer, String> byPage = new LinkedHashMap<>();
        TreeSet<Integer> failed = new TreeSet<>();
        ExecutorService exec = Executors.newFixedThreadPool(Math.min(o.getConcurrency(), targets.size()));
        try {
            List<Future<String>> futures = new ArrayList<>(targets.size());
            for (int p : targets) {
                futures.add(exec.submit(() -> ocrOnePage(pdf, p, taskDir)));
            }
            for (int i = 0; i < futures.size(); i++) {
                int p = targets.get(i);
                String text;
                try {
                    text = futures.get(i).get();
                } catch (Exception e) {
                    text = "Error: " + e;
                }
                if (text.startsWith("Error:")) {
                    log.warn("ocr page {} failed: {}", p, snippet(text));
                    failed.add(p);
                } else {
                    byPage.put(p, text);
                }
            }
        } finally {
            exec.shutdownNow();
        }
        if (failed.size() == targets.size()) {
            log.warn("OCR 通道 {} 页全部失败，回退视觉管线", targets.size());
            return null;
        }
        for (var e : texts.entrySet()) {
            int p = e.getKey();
            if (p >= start && p <= end && !byPage.containsKey(p) && !failed.contains(p)) {
                byPage.put(p, e.getValue());
            }
        }
        log.info("ocr 通道完成 pages={} ocrPages={} textPages={} failed={}",
                end - start + 1, targets.size(), byPage.size() - targets.size() + failed.size(), failed.size());
        return new PageTexts(byPage, failed);
    }

    /**
     * 文字层里中日韩字符的占比，分母是非空白字符数；空文本或取不到文字返回 0。
     * 用来识别「有文字层但文字层不可用」的页（缺 ToUnicode 的 PDF 最常见）。
     */
    private static double cjkRatio(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        int total = 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (isCjk(c)) {
                cjk++;
            }
        }
        return total == 0 ? 0.0 : (double) cjk / total;
    }

    /** 中日韩文字：CJK 统一表意文字、扩展 A、兼容表意文字、日文假名；只数表意文字，标点不计。 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0x3040 && c <= 0x30FF);
    }

    /** 单页：按 OCR 的 dpi 栅格化 → 送 OCR 服务。返回文本或 {@code "Error: ..."}。 */
    private String ocrOnePage(Path pdf, int page, Path taskDir) {
        AgentProperties.Ocr o = props.getOcr();
        List<byte[]> images;
        try {
            images = rasterizeBytes(pdf, page, page, "pgocr" + page, taskDir,
                    o.getRasterizeDpi(), o.getMaxImageDimension(), o.getJpegQuality());
        } catch (Exception e) {
            return "Error: 栅格化第 " + page + " 页失败：" + e.getMessage();
        }
        if (images.isEmpty()) {
            return "Error: 第 " + page + " 页栅格化无产物";
        }
        return ocrClient.ocrImage(images.get(0), "page-" + page + ".jpg");
    }

    /** OCR/文字层文本的字段抽取：按页分块并发调文本模型，命中按页码归并进 {@code acc}。 */
    private void extractFromPageTexts(PageTexts pt, List<String> fields, int total, int start, int end,
                                      LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc,
                                      TreeSet<Integer> failedPages) {
        List<List<TextPiece>> chunks = chunkPieces(pt.byPage, props.getPdfs().getTextChunkSize());
        failedPages.addAll(pt.failedPages);
        if (chunks.isEmpty()) {
            return;
        }
        int pool = Math.min(props.getPdfs().getConcurrency(), chunks.size());
        ExecutorService exec = Executors.newFixedThreadPool(pool);
        try {
            List<Future<List<Extraction.Hit>>> futures = new ArrayList<>(chunks.size());
            for (List<TextPiece> chunk : chunks) {
                futures.add(exec.submit(() -> extractTextChunk(chunk, fields, total)));
            }
            for (int i = 0; i < futures.size(); i++) {
                List<TextPiece> chunk = chunks.get(i);
                List<Extraction.Hit> hits;
                try {
                    hits = futures.get(i).get();
                } catch (Exception e) {
                    log.warn("ocr-text extract chunk {} failed: {}", i + 1, e.toString());
                    hits = null;
                }
                if (hits == null) {
                    for (TextPiece piece : chunk) {
                        failedPages.add(piece.page());
                    }
                    continue;
                }
                List<Integer> pagesInChunk = pagesOf(chunk);
                for (Extraction.Hit h : hits) {
                    if (h.value() == null || h.value().isBlank()) {
                        continue;
                    }
                    acc.computeIfAbsent(h.field(), x -> new LinkedHashMap<>())
                            .computeIfAbsent(h.value().trim(), x -> new TreeSet<>())
                            .addAll(clampPages(h.pages(), pagesInChunk, start, end));
                }
            }
        } finally {
            exec.shutdownNow();
        }
    }

    /** 一块文本的字段抽取；失败返回 {@code null}，由调用方按块失败处理。 */
    private List<Extraction.Hit> extractTextChunk(List<TextPiece> chunk, List<String> fields, int total) {
        String prompt = "以下是同一份文档的识别文本（全文共 " + total + " 页），每页以【第 N 页】标记开始。\n"
                + "文本由 OCR/文字层取得，可能有少量错字或断行，取值时尽量保持原文写法、不要自行改写。\n"
                + "只依据这段文本，为下面每个字段找出它的值以及出现的绝对页码（用【第 N 页】里的页码）。"
                + "同一字段多页出现就都列页码；这段文本里找不到该字段就不要输出它（不要编造）。\n"
                + "只输出一个 JSON 对象，不要解释、不要代码围栏。需要抽取的字段：\n"
                + String.join("\n", fields) + "\n\n输出格式（严格）：\n"
                + "{\"hits\":[{\"field\":\"字段名\",\"value\":\"值\",\"pages\":[页码数字]}]}\n\n"
                + "--- 文档内容 ---\n" + pageMarkedText(chunk);
        String answer = visionClient.askTextOnly(prompt);
        if (answer.startsWith("Error:")) {
            log.warn("ocr-text extract chunk error: {}", snippet(answer));
            return null;
        }
        List<Extraction.Hit> hits = Extraction.parseHits(mapper, answer);
        if (hits == null) {
            log.warn("ocr-text extract chunk answer not parseable");
            return null;
        }
        return hits;
    }

    private static String pageMarkedText(List<TextPiece> chunk) {
        StringBuilder sb = new StringBuilder();
        for (TextPiece piece : chunk) {
            sb.append("【第 ").append(piece.page()).append(" 页】\n")
                    .append(piece.text().trim()).append("\n\n");
        }
        return sb.toString();
    }

    /** 按页装箱成文本块：每块累计字符数不超 {@code chunkSize}；超长单页按字符窗口切分（页码不变）。 */
    private static List<List<TextPiece>> chunkPieces(LinkedHashMap<Integer, String> byPage, int chunkSize) {
        List<List<TextPiece>> chunks = new ArrayList<>();
        List<TextPiece> current = new ArrayList<>();
        int size = 0;
        for (var e : byPage.entrySet()) {
            String text = e.getValue() == null ? "" : e.getValue().trim();
            if (text.isEmpty()) {
                continue;
            }
            for (int off = 0; off < text.length(); off += chunkSize) {
                int len = Math.min(chunkSize, text.length() - off);
                if (size + len > chunkSize && !current.isEmpty()) {
                    chunks.add(current);
                    current = new ArrayList<>();
                    size = 0;
                }
                current.add(new TextPiece(e.getKey(), text.substring(off, off + len)));
                size += len;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private static List<Integer> pagesOf(List<TextPiece> chunk) {
        return chunk.stream().map(TextPiece::page).distinct().sorted().toList();
    }

    /**
     * 校正模型给的页码：只保留落在本块内的页码；一个都不落在本块内就退回整块页码——
     * 宁可页码粗一点，也不把已经抽到的值丢掉。
     */
    private static List<Integer> clampPages(List<Integer> pages, List<Integer> pagesInChunk, int start, int end) {
        List<Integer> ok = new ArrayList<>();
        for (Integer p : pages) {
            if (p != null && p >= start && p <= end && pagesInChunk.contains(p) && !ok.contains(p)) {
                ok.add(p);
            }
        }
        return ok.isEmpty() ? pagesInChunk : ok;
    }

    // ---- 结果构造 / 解析 ----

    private String buildExtractResult(List<String> fields,
                                      LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc,
                                      TreeSet<Integer> failedPages, int total, int start, int scanEnd,
                                      boolean incomplete, String pipeline) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        boolean complete = failedPages.isEmpty() && !incomplete;
        root.put("status", complete ? "complete" : "partial");
        root.put("total_units", total);
        ArrayNode scanned = root.putArray("units_scanned");
        scanned.add(start);
        scanned.add(scanEnd);
        root.put("incomplete", incomplete);
        root.put("pipeline", pipeline);

        if (incomplete) {
            root.put("hint", "只扫描了前 " + (scanEnd - start + 1) + " 页（上限所致），还有第 "
                    + (scanEnd + 1) + " 页起未处理；如需要，用 page_range=\"" + (scanEnd + 1) + "-…\" 再调一次并自行合并。");
        }

        ArrayNode results = root.putArray("results");
        LinkedHashSet<String> emitted = new LinkedHashSet<>();
        for (String field : fields) {
            emitted.add(field);
            LinkedHashMap<String, TreeSet<Integer>> byValue = acc.get(field);
            if (byValue == null || byValue.isEmpty()) {
                ObjectNode r = results.addObject();
                r.put("field", field);
                r.putNull("value");
                r.putArray("units");
                continue;
            }
            for (var e : byValue.entrySet()) {
                ObjectNode r = results.addObject();
                r.put("field", field);
                r.put("value", e.getKey());
                ArrayNode units = r.putArray("units");
                for (int p : e.getValue()) {
                    units.add(p);
                }
            }
        }
        // 模型可能多抽了未请求的字段，附上以免丢信息。
        for (var e : acc.entrySet()) {
            if (emitted.contains(e.getKey())) {
                continue;
            }
            for (var ve : e.getValue().entrySet()) {
                ObjectNode r = results.addObject();
                r.put("field", e.getKey());
                r.put("value", ve.getKey());
                ArrayNode units = r.putArray("units");
                for (int p : ve.getValue()) {
                    units.add(p);
                }
            }
        }
        ArrayNode failed = root.putArray("failed_units");
        for (int p : failedPages) {
            failed.add(p);
        }
        return mapper.writeValueAsString(root);
    }

    // ---- 来源解析 / 栅格化 / 基础设施（两工具共用） ----

    /** URL→下载到沙箱(source.pdf)；本地→沙箱内校验。返回文件；本地越界/不存在返回 null。 */
    private Path resolveSource(String pdfSource, AgentProperties.Pdfs cfg, Path taskDir) throws Exception {
        if (isHttpUrl(pdfSource)) {
            URI uri = validateUrl(pdfSource, cfg);
            return download(uri, taskDir, cfg.getMaxDownloadBytes());
        }
        Path local = SandboxPaths.resolveWithin(taskDir, pdfSource);
        if (local == null || !Files.isRegularFile(local)) {
            return null;
        }
        return local;
    }

    private static boolean isDownloaded(String pdfSource) {
        return isHttpUrl(pdfSource);
    }

    /** 每批喂视觉模型的页数 = min(pdf.pages-per-call, vision.max-images)，至少 1。 */
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

    private int pageCount(Path pdf) throws Exception {
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

    private Path download(URI uri, Path taskDir, int maxBytes) throws Exception {
        Path target = taskDir.resolve("source.pdf");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(120))
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("下载 PDF 失败 HTTP " + resp.statusCode());
        }
        long total = 0;
        try (InputStream in = resp.body(); OutputStream os = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("PDF 超过体积上限 " + maxBytes + " 字节");
                }
                os.write(buf, 0, n);
            }
        }
        if (total == 0) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("下载到的 PDF 为空");
        }
        return target;
    }

    private URI validateUrl(String url, AgentProperties.Pdfs cfg) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("pdf_url 为空");
        }
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("pdf_url 必须是 http/https，收到：" + url);
        }
        List<String> allow = cfg.getSsrfAllowlist();
        if (allow != null && !allow.isEmpty()) {
            String host = uri.getHost();
            if (host == null) {
                throw new IllegalArgumentException("无法解析主机名：" + url);
            }
            String h = host.toLowerCase();
            boolean ok = false;
            for (String entry : allow) {
                String e = entry == null ? "" : entry.trim().toLowerCase();
                if (!e.isEmpty() && (h.equals(e) || h.endsWith("." + e))) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new IllegalArgumentException("主机不在允许的白名单内：" + host);
            }
        }
        return uri;
    }

    private static int[] resolveRange(String pageRange, int total) {
        if (pageRange == null || pageRange.isBlank()) {
            return new int[]{1, total};
        }
        String s = pageRange.trim().replace('－', '-');
        int dash = s.indexOf('-');
        int start;
        int end;
        if (dash < 0) {
            start = Integer.parseInt(s.trim());
            end = start;
        } else {
            String l = s.substring(0, dash).trim();
            String r = s.substring(dash + 1).trim();
            start = l.isEmpty() ? 1 : Integer.parseInt(l);
            end = r.isEmpty() ? total : Integer.parseInt(r);
        }
        start = Math.max(1, start);
        end = Math.min(end, total);
        if (start > end) {
            throw new IllegalArgumentException("页范围无效：" + pageRange + "（总页数 " + total + "）");
        }
        return new int[]{start, end};
    }

    private static boolean isHttpUrl(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim().toLowerCase();
        return t.startsWith("http://") || t.startsWith("https://");
    }

    // parseFields 统一用 Extraction.parseFields()

    private static void markFailed(TreeSet<Integer> failedPages, int[] range) {
        for (int p = range[0]; p <= range[1]; p++) {
            failedPages.add(p);
        }
    }

    /** 清理临时页图；{@code alsoDelete} 非空则一并删该下载来的源文件（本地来源传 null 不删源文件）。 */
    private void cleanup(Path taskDir, String alsoDelete) {
        try {
            if (taskDir != null && Files.isDirectory(taskDir)) {
                try (var stream = Files.list(taskDir)) {
                    stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("pg") && n.toLowerCase().endsWith(".jpg");
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

    /** OCR 文本通道的一块文本：绝对页码 + 该页（或该页的一段）文本。 */
    private record TextPiece(int page, String text) {
    }

    /** OCR 文本通道的汇总：成功页→文本，以及 OCR 失败的页。 */
    private static final class PageTexts {
        final LinkedHashMap<Integer, String> byPage;
        final TreeSet<Integer> failedPages;

        PageTexts(LinkedHashMap<Integer, String> byPage, TreeSet<Integer> failedPages) {
            this.byPage = byPage;
            this.failedPages = failedPages;
        }
    }

    /** 单批输出。 */
    private static final class BatchOut {
        final List<Extraction.Hit> hits;
        final boolean error;

        private BatchOut(List<Extraction.Hit> hits, boolean error) {
            this.hits = hits;
            this.error = error;
        }

        static BatchOut ok(List<Extraction.Hit> hits) {
            return new BatchOut(hits, false);
        }

        static BatchOut failed() {
            return new BatchOut(List.of(), true);
        }
    }
}

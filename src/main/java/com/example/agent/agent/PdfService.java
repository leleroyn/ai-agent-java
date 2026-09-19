package com.example.agent.agent;

import com.example.agent.config.AgentProperties;
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
 *   <li>{@link #understand} —— {@code understand_pdf}：把一批页栅格化后连同自由问题交给视觉模型，
 *       返回自然语言（带绝对页码）。用于对某几页/小文档做自由问答、总结。</li>
 *   <li>{@link #extractFields} —— {@code extract_pdf_fields}：服务端<b>遍历整篇</b>，分批并发地按
 *       {@code fields} 结构化抽取，再<b>确定性归并</b>（同字段同值并页码、去重），返回
 *       {@code results:[{field,value,pages}]} + {@code pages_scanned} + {@code incomplete}。
 *       召回由服务端遍历兜底、不依赖主模型分页；语义总结/取舍交给主模型。</li>
 * </ul>
 *
 * <p>与 {@link VisionClient} 同源解耦：页面图像只发到 {@code agent.vision} 端点，主模型只拿文字。
 * 栅格化用 {@code pdftoppm}（poppler-utils），产物写任务沙箱、用完即删；本地来源的源文件绝不删。
 */
@Component
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);
    private static final Pattern PAGES = Pattern.compile("^Pages:\\s*(\\d+)", Pattern.MULTILINE);
    private static final Pattern TRAIL_NUM = Pattern.compile("-(\\d+)\\.[A-Za-z0-9]+$");

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final VisionClient visionClient;
    private final HttpClient http;

    public PdfService(AgentProperties props,
                      @Qualifier("agentScopeObjectMapper") ObjectMapper mapper,
                      VisionClient visionClient) {
        this.props = props;
        this.mapper = mapper;
        this.visionClient = visionClient;
        this.http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 自由问答（{@code understand_pdf}）。处理一批页（≤ {@code min(pages-per-call, vision.max-images)}）；
     * 超过则返回提示让调用方用 {@code page_range} 分批。返回自然语言，或 {@code "Error:"}/提示。
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
                        + " 页）超过单次上限 " + cap + " 页。若要抽取关键信息请改用 extract_pdf_fields"
                        + "（它会服务端遍历整篇）；若只是问答，请用 page_range 分批，例如先 \""
                        + start + "-" + nextEnd + "\"、再往后，然后自行汇总。";
            }
            List<String> urls = rasterize(pdf, start, end, 0, taskDir);
            if (urls.isEmpty()) {
                return "Error: 栅格化第 " + start + "-" + end + " 页失败（可能不是有效 PDF）。";
            }
            String q = (question == null || question.isBlank())
                    ? "请逐页转录并说明这一页的标题与主要内容。" : question.trim();
            return visionClient.extractFromDataUrls(urls, buildQaPrompt(start, end, total, q));
        } catch (Exception e) {
            log.error("pdf understand failed src={}", pdfSource, e);
            return "Error: 处理 PDF 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            cleanup(taskDir, downloaded ? "source.pdf" : null);
        }
    }

    /**
     * 结构化抽取（{@code extract_pdf_fields}）：服务端遍历整篇，分批并发抽取 {@code fields}，
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
        List<String> fields = parseFields(fieldsRaw);
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
            return buildExtractResult(fields, acc, failedPages, total, start, scanEnd, incomplete);
        } catch (Exception e) {
            log.error("pdf extract failed src={}", pdfSource, e);
            return "Error: 处理 PDF 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            cleanup(taskDir, downloaded ? "source.pdf" : null);
        }
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

    /** 自由问答提示（understand_pdf）。 */
    private String buildQaPrompt(int a, int b, int total, String question) {
        return "这是同一份文档的第 " + a + " 到第 " + b + " 页（全文共 " + total + " 页中这一段）。"
                + "按顺序第 1 张图=第 " + a + " 页，第 2 张图=第 " + (a + 1) + " 页，依此类推。\n"
                + "只依据这些图片内容作答，不要编造；凡是引用了文档中的具体内容，请标注它出现的绝对页码。\n要求："
                + question;
    }

    // ---- 结果构造 / 解析 ----

    private String buildExtractResult(List<String> fields,
                                      LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc,
                                      TreeSet<Integer> failedPages, int total, int start, int scanEnd,
                                      boolean incomplete) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        boolean complete = failedPages.isEmpty() && !incomplete;
        root.put("status", complete ? "complete" : "partial");
        root.put("total_pages", total);
        ArrayNode scanned = root.putArray("pages_scanned");
        scanned.add(start);
        scanned.add(scanEnd);
        root.put("incomplete", incomplete);
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
                r.putArray("pages");
                continue;
            }
            for (var e : byValue.entrySet()) {
                ObjectNode r = results.addObject();
                r.put("field", field);
                r.put("value", e.getKey());
                ArrayNode pages = r.putArray("pages");
                for (int p : e.getValue()) {
                    pages.add(p);
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
                ArrayNode pages = r.putArray("pages");
                for (int p : ve.getValue()) {
                    pages.add(p);
                }
            }
        }
        ArrayNode failed = root.putArray("failed_pages");
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

    private List<String> rasterize(Path pdf, int a, int b, int batchId, Path taskDir) throws Exception {
        AgentProperties.Pdfs cfg = props.getPdfs();
        String prefix = taskDir.resolve("pg" + batchId).toString();
        List<String> cmd = new ArrayList<>(List.of("pdftoppm"));
        if (cfg.getMaxImageDimension() > 0) {
            cmd.add("-scale-to");
            cmd.add(String.valueOf(cfg.getMaxImageDimension()));
        }
        cmd.add("-jpeg");
        cmd.add("-jpegopt");
        cmd.add("quality=" + cfg.getJpegQuality());
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
        final String stem = "pg" + batchId + "-";
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(taskDir)) {
            stream.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.startsWith(stem) && n.toLowerCase().endsWith(".jpg")) {
                    files.add(p);
                }
            });
        }
        files.sort(Comparator.comparingInt(PdfService::trailingNumber));
        List<String> urls = new ArrayList<>(files.size());
        for (Path f : files) {
            byte[] bytes = Files.readAllBytes(f);
            urls.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes));
            Files.deleteIfExists(f);
        }
        return urls;
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

    private static List<String> parseFields(String raw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[;；,，、\\n\\r]+")) {
                String f = part.trim();
                if (!f.isEmpty()) {
                    set.add(f);
                }
            }
        }
        return new ArrayList<>(set);
    }

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

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
import java.util.Set;
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
 * 大/多页扫描 PDF 的抽取流水线：下载 → 数页 → 逐页栅格化（poppler pdftoppm）→ 每 N 页一批交给
 * 视觉模型（{@code agent.vision}）抽取调用方指定的关键信息 → 合并去重 → 只回字段值 + 页码。
 *
 * <p><b>与主模型解耦</b>：PDF/页面图像只发到 VL 端点，主模型只拿到最终紧凑 JSON。
 *
 * <p><b>不做 OCR、不做自动断点续传</b>（按方案取舍）：扫描页本就是图像，VL 直接读；页数不设硬上限，
 * 由上层任务级超时兜底。单批 VL 调用有独立超时；某批失败记入 failed_pages 继续，不整体失败。
 *
 * <p>栅格化用 {@code pdftoppm}（镜像内 poppler-utils），产物写任务沙箱、用完即删；并发上限
 * {@code agent.pdf.concurrency}，因此同时驻留磁盘的页面图受控。
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
     * @param pdfUrl    PDF 的 http/https 地址
     * @param fieldsRaw 用户要抽取的关键信息，用 ; 换行 、 , 分隔
     * @param pageRange 可选页范围 "a-b"/"a"/"a-"；null 或空=全部页
     * @param taskDir   任务沙箱（下载与栅格化临时文件写这里，随任务清理）
     * @return 紧凑结果 JSON 文本；出错返回 {@code "Error: ..."}
     */
    public String extract(String pdfUrl, String fieldsRaw, String pageRange, Path taskDir) {
        AgentProperties.Pdfs cfg = props.getPdfs();
        if (!props.getVision().isEnabled()) {
            return "Error: 视觉功能未启用（agent.vision.enabled=false），无法理解 PDF。";
        }
        List<String> fields = parseFields(fieldsRaw);
        if (fields.isEmpty()) {
            return "Error: fields 为空，请说明要抽取哪些关键信息。";
        }
        Path pdf = null;
        try {
            URI uri = validateUrl(pdfUrl, cfg);
            pdf = download(uri, taskDir, cfg.getMaxDownloadBytes());
            int total = pageCount(pdf);
            int[] range = resolveRange(pageRange, total);
            int start = range[0];
            int end = range[1];
            log.info("pdf extract url={} pages={} range={}-{} fields={}",
                    pdfUrl, total, start, end, fields.size());
            final Path pdfFile = pdf;

            int k = cfg.getPagesPerCall();
            int pool = cfg.getConcurrency();
            // 合并累加器：field -> (value -> 命中页集合)
            LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc = new LinkedHashMap<>();
            TreeSet<Integer> failedPages = new TreeSet<>();
            ExecutorService exec = Executors.newFixedThreadPool(pool);
            List<Future<BatchOut>> futures = new ArrayList<>();
            List<int[]> ranges = new ArrayList<>();
            int batchId = 0;
            try {
                for (int a = start; a <= end; a += k) {
                    int b = Math.min(a + k - 1, end);
                    final int fa = a;
                    final int fb = b;
                    final int bid = batchId++;
                    ranges.add(new int[]{fa, fb});
                    futures.add(exec.submit(() -> runBatch(pdfFile, fa, fb, bid, fields, total, taskDir)));
                }
                for (int i = 0; i < futures.size(); i++) {
                    int[] rr = ranges.get(i);
                    BatchOut out;
                    try {
                        out = futures.get(i).get();
                    } catch (Exception e) {
                        log.warn("pdf batch {}-{} failed: {}", rr[0], rr[1], e.toString());
                        for (int p = rr[0]; p <= rr[1]; p++) {
                            failedPages.add(p);
                        }
                        continue;
                    }
                    if (out.error) {
                        for (int p = rr[0]; p <= rr[1]; p++) {
                            failedPages.add(p);
                        }
                        continue;
                    }
                    for (Hit h : out.hits) {
                        if (h.value == null || h.value.isBlank()) {
                            continue;
                        }
                        acc.computeIfAbsent(h.field, x -> new LinkedHashMap<>())
                                .computeIfAbsent(h.value.trim(), x -> new TreeSet<>())
                                .addAll(h.pages);
                    }
                }
            } finally {
                exec.shutdownNow();
            }
            return buildResult(fields, acc, failedPages, total, start, end);
        } catch (Exception e) {
            log.error("pdf extract failed url={}", pdfUrl, e);
            return "Error: 处理 PDF 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            cleanup(pdf, taskDir);
        }
    }

    /** 单批：栅格化 [a..b] 页 → VL 抽取 → 解析 hits。任一步失败即 error=true。 */
    private BatchOut runBatch(Path pdf, int a, int b, int batchId,
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
        String answer = visionClient.extractFromDataUrls(dataUrls, buildPrompt(a, b, fields));
        if (answer.startsWith("Error:")) {
            log.warn("VL batch {}-{} returned error: {}", a, b, answer.substring(0, Math.min(160, answer.length())));
            return BatchOut.failed();
        }
        List<Hit> hits = parseHits(answer);
        if (hits == null) {
            log.warn("VL batch {}-{} answer not parseable as JSON", a, b);
            return BatchOut.failed();
        }
        return BatchOut.ok(hits);
    }

    private String buildPrompt(int a, int b, List<String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是同一份文档的第 ").append(a).append(" 到 ").append(b).append(" 页，")
                .append("按顺序第 1 张图=第 ").append(a).append(" 页，第 2 张图=第 ").append(a + 1)
                .append(" 页，依此类推。\n")
                .append("只依据图片内容，为下面每个字段找出它的值以及出现的页码（用上面标注的绝对页码）。")
                .append("同一字段可能在多页出现，请都列出页码。图中找不到该字段则 value 用 null。\n")
                .append("不要编造，不要解释，只输出一个 JSON 对象，不要代码围栏。需要抽取的字段：\n")
                .append(String.join("\n", fields)).append("\n\n")
                .append("输出格式（严格）：\n")
                .append("{\"hits\":[{\"field\":\"字段名\",\"value\":\"值或null\",\"pages\":[页码数字]}]}");
        return sb.toString();
    }

    /** 栅格化 [a..b] 页为 JPEG data URL（按页升序），用完即删临时文件。 */
    private List<String> rasterize(Path pdf, int a, int b, int batchId, Path taskDir) throws Exception {
        AgentProperties.Pdfs cfg = props.getPdfs();
        String prefix = taskDir.resolve("pg" + batchId).toString();
        // 参数顺序必须正确：pdftoppm 对用法错误会直接打 usage 并以退出码 99 退出。
        // -scale-to / -jpegopt 都是带值的选项，不能把它们和自己的值拆开，也不能插到别的选项中间。
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

    /** 从 pdftoppm 文件名末尾 -NNN.jpg 取页序号，用于排序。 */
    private static int trailingNumber(Path p) {
        Matcher m = TRAIL_NUM.matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** pdfinfo 取总页数。 */
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

    /** 下载 PDF 到沙箱，超过体积上限即中止。 */
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

    /** 校验 URL 协议与 SSRF 白名单。 */
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

    /** 宽松解析 VL 返回的 JSON：取首个平衡的 {..} 或 [..]。解析失败返回 null。 */
    private List<Hit> parseHits(String text) {
        String json = extractJson(text);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode hits = root.isArray() ? root : root.path("hits");
            if (!hits.isArray()) {
                return null;
            }
            List<Hit> list = new ArrayList<>();
            for (JsonNode h : hits) {
                String field = textOrNull(h.path("field"));
                if (field == null || field.isBlank()) {
                    continue;
                }
                String value = textOrNull(h.path("value"));
                List<Integer> pages = new ArrayList<>();
                JsonNode p = h.path("pages");
                if (p.isArray()) {
                    for (JsonNode pn : p) {
                        if (pn.isNumber()) {
                            pages.add(pn.asInt());
                        } else if (pn.isTextual()) {
                            try {
                                pages.add(Integer.parseInt(pn.asText().trim()));
                            } catch (NumberFormatException ignored) {
                                // 非数字页码忽略
                            }
                        }
                    }
                } else if (p.isNumber()) {
                    pages.add(p.asInt());
                }
                list.add(new Hit(field.trim(), value, pages));
            }
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从可能夹带解释的文本里抠出第一段平衡的 JSON 对象/数组。 */
    private static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        int obj = text.indexOf('{');
        int arr = text.indexOf('[');
        int begin;
        char open;
        char close;
        if (obj < 0 && arr < 0) {
            return null;
        }
        if (arr < 0 || (obj >= 0 && obj < arr)) {
            begin = obj;
            open = '{';
            close = '}';
        } else {
            begin = arr;
            open = '[';
            close = ']';
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = begin; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(begin, i + 1);
                }
            }
        }
        return null;
    }

    private String buildResult(List<String> fields,
                               LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc,
                               TreeSet<Integer> failedPages, int total, int start, int end) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("status", failedPages.isEmpty() ? "complete" : "partial");
        root.put("total_pages", total);
        ArrayNode processed = root.putArray("pages_processed");
        processed.add(start);
        processed.add(end);

        ArrayNode results = root.putArray("results");
        Set<String> emitted = new LinkedHashSet<>();
        for (String field : fields) {
            LinkedHashMap<String, TreeSet<Integer>> byValue = acc.get(field);
            emitted.add(field);
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
        // 模型可能返回了未在 fields 里、但确实抽到的字段，一并附上以免丢信息。
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

    /** 删除本次下载的 PDF 与残留的页面临时图（taskDir 会随任务清理，这里提前回收磁盘）。 */
    private void cleanup(Path pdf, Path taskDir) {
        try {
            if (pdf != null) {
                Files.deleteIfExists(pdf);
            }
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
            }
        } catch (Exception ignored) {
            // 清理失败不影响结果
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        return n.asText();
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /** 单字段命中。 */
    private static final class Hit {
        final String field;
        final String value;
        final List<Integer> pages;

        Hit(String field, String value, List<Integer> pages) {
            this.field = field;
            this.value = value;
            this.pages = pages;
        }
    }

    /** 单批输出。 */
    private static final class BatchOut {
        final List<Hit> hits;
        final boolean error;

        private BatchOut(List<Hit> hits, boolean error) {
            this.hits = hits;
            this.error = error;
        }

        static BatchOut ok(List<Hit> hits) {
            return new BatchOut(hits, false);
        }

        static BatchOut failed() {
            return new BatchOut(List.of(), true);
        }
    }
}

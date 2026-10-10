package com.jtzj.agent.core.service;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.support.Extraction;
import com.jtzj.agent.core.support.SandboxPaths;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * TXT 文档服务端能力：分块 + 纯文本调视觉模型抽取/理解。
 *
 * <p>与 {@link PdfService} 同构：服务端遍历整篇（按字符数分块），分批并发调 {@link VisionClient#askTextOnly}，
 * 确定性归并后返回结构化结果。"页码"概念映射为"块序号"。
 */
@Component
public class TextService {

    private static final Logger log = LoggerFactory.getLogger(TextService.class);

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final VisionClient visionClient;
    private final HttpClient http;

    public TextService(AgentProperties props,
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
     * 结构化抽取（{@code extract_document_fields} TXT 分支）。
     */
    public String extractFields(String txtSource, String fieldsRaw, Path taskDir) {
        AgentProperties.Pdfs cfg = props.getPdfs();
        if (!props.getVision().isEnabled()) {
            return "Error: 视觉功能未启用（agent.vision.enabled=false），无法处理文档。";
        }
        List<String> fields = parseFields(fieldsRaw);
        if (fields.isEmpty()) {
            return "Error: fields 为空，请指定要抽取的字段。";
        }
        try {
            Path file = resolveSource(txtSource, cfg, taskDir);
            if (file == null) {
                return "Error: 本地文档路径必须在任务工作目录内且存在：" + txtSource;
            }
            boolean downloaded = isHttpUrl(txtSource);
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } finally {
                if (downloaded) {
                    Files.deleteIfExists(file);
                }
            }
            if (content.isBlank()) {
                return "Error: 文件内容为空。";
            }

            int chunkSize = cfg.getTextChunkSize();
            int overlap = cfg.getTextChunkOverlap();
            List<String> chunks = chunk(content, chunkSize, overlap);
            int totalChunks = chunks.size();
            int pool = cfg.getConcurrency();
            log.info("text extract src={} chars={} chunks={} pool={} fields={}",
                    txtSource, content.length(), totalChunks, pool, fields.size());

            LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc = new LinkedHashMap<>();
            TreeSet<Integer> failedChunks = new TreeSet<>();
            ExecutorService exec = Executors.newFixedThreadPool(Math.min(pool, totalChunks));
            List<Future<ChunkOut>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < totalChunks; i++) {
                    final int idx = i;
                    final String chunk = chunks.get(i);
                    futures.add(exec.submit(() -> extractChunk(chunk, idx + 1, totalChunks, fields)));
                }
                for (int i = 0; i < futures.size(); i++) {
                    ChunkOut out;
                    try {
                        out = futures.get(i).get();
                    } catch (Exception e) {
                        failedChunks.add(i + 1);
                        continue;
                    }
                    if (out.error) {
                        failedChunks.add(i + 1);
                        continue;
                    }
                    for (Extraction.Hit h : out.hits) {
                        if (h.value() == null || h.value().isBlank()) continue;
                        acc.computeIfAbsent(h.field(), x -> new LinkedHashMap<>())
                                .computeIfAbsent(h.value().trim(), x -> new TreeSet<>())
                                .addAll(h.pages());
                    }
                }
            } finally {
                exec.shutdownNow();
            }
            return buildResult(fields, acc, failedChunks, totalChunks);
        } catch (Exception e) {
            log.error("text extract failed src={}", txtSource, e);
            return "Error: 处理 TXT 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * 自由问答（{@code understand_document} TXT 分支）。
     */
    public String understand(String txtSource, String question, Path taskDir) {
        AgentProperties.Pdfs cfg = props.getPdfs();
        if (!props.getVision().isEnabled()) {
            return "Error: 视觉功能未启用（agent.vision.enabled=false），无法处理文档。";
        }
        try {
            Path file = resolveSource(txtSource, cfg, taskDir);
            if (file == null) {
                return "Error: 本地文档路径必须在任务工作目录内且存在：" + txtSource;
            }
            boolean downloaded = isHttpUrl(txtSource);
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } finally {
                if (downloaded) {
                    Files.deleteIfExists(file);
                }
            }
            if (content.isBlank()) {
                return "Error: 文件内容为空。";
            }

            int chunkSize = cfg.getTextChunkSize();
            int overlap = cfg.getTextChunkOverlap();
            List<String> chunks = chunk(content, chunkSize, overlap);
            int totalChunks = chunks.size();
            log.info("text understand src={} chars={} chunks={}", txtSource, content.length(), totalChunks);

            String q = (question == null || question.isBlank())
                    ? "请转录并总结这份文档的主要内容。" : question.trim();

            if (totalChunks == 1) {
                return visionClient.askTextOnly(buildQaPrompt(chunks.get(0), 1, 1, q));
            }
            // 多块：逐块问答，拼接结果
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < totalChunks; i++) {
                String answer = visionClient.askTextOnly(buildQaPrompt(chunks.get(i), i + 1, totalChunks, q));
                if (answer.startsWith("Error:")) {
                    sb.append("[第 ").append(i + 1).append('/').append(totalChunks).append(" 段处理失败]\n");
                } else {
                    sb.append(answer).append('\n');
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("text understand failed src={}", txtSource, e);
            return "Error: 处理 TXT 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ---- 内部 ----

    private ChunkOut extractChunk(String text, int chunkNum, int totalChunks, List<String> fields) {
        String prompt = "这是同一份文档的第 " + chunkNum + " 段（全文共 " + totalChunks
                + " 段中这一段）。\n"
                + "只依据这段文本内容，为下面每个字段找出它的值以及出现的段号（用上面标好的段号）。"
                + "同一字段可能多段出现，都列出段号；这段文本中找不到该字段则不要输出它（不要编造）。\n"
                + "只输出一个 JSON 对象，不要解释、不要代码围栏。需要抽取的字段：\n"
                + String.join("\n", fields) + "\n\n输出格式（严格）：\n"
                + "{\"hits\":[{\"field\":\"字段名\",\"value\":\"值\",\"pages\":[段号数字]}]}\n\n"
                + "--- 文档内容 ---\n" + text;
        String answer = visionClient.askTextOnly(prompt);
        if (answer.startsWith("Error:")) {
            log.warn("text extract chunk {}/{} error: {}", chunkNum, totalChunks, answer);
            return ChunkOut.failed();
        }
        List<Extraction.Hit> hits = Extraction.parseHits(mapper, answer);
        if (hits == null) {
            log.warn("text extract chunk {}/{} answer not parseable", chunkNum, totalChunks);
            return ChunkOut.failed();
        }
        return ChunkOut.ok(hits);
    }

    private String buildQaPrompt(String text, int chunkNum, int totalChunks, String question) {
        return "这是同一份文档的第 " + chunkNum + " 段（全文共 " + totalChunks + " 段中这一段）。\n"
                + "只依据这段文本内容作答，不要编造；引用了文档中的具体内容请标注它出现的段号。\n要求："
                + question + "\n\n--- 文档内容 ---\n" + text;
    }

    private String buildResult(List<String> fields,
                               LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc,
                               TreeSet<Integer> failedChunks, int totalChunks) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        boolean complete = failedChunks.isEmpty();
        root.put("status", complete ? "complete" : "partial");
        root.put("total_chunks", totalChunks);
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
                for (int p : e.getValue()) pages.add(p);
            }
        }
        for (var e : acc.entrySet()) {
            if (emitted.contains(e.getKey())) continue;
            for (var ve : e.getValue().entrySet()) {
                ObjectNode r = results.addObject();
                r.put("field", e.getKey());
                r.put("value", ve.getKey());
                ArrayNode pages = r.putArray("pages");
                for (int p : ve.getValue()) pages.add(p);
            }
        }
        ArrayNode failed = root.putArray("failed_chunks");
        for (int p : failedChunks) failed.add(p);
        return mapper.writeValueAsString(root);
    }

    private List<String> chunk(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int step = size - overlap;
        for (int start = 0; start < len; start += step) {
            int end = Math.min(start + size, len);
            chunks.add(text.substring(start, end));
            if (end >= len) break;
        }
        return chunks;
    }

    private Path resolveSource(String source, AgentProperties.Pdfs cfg, Path taskDir) throws Exception {
        if (isHttpUrl(source)) {
            URI uri = URI.create(source.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("URL 必须是 http/https，收到：" + source);
            }
            return download(uri, taskDir, cfg.getMaxDownloadBytes());
        }
        Path local = SandboxPaths.resolveWithin(taskDir, source);
        if (local == null || !Files.isRegularFile(local)) {
            return null;
        }
        return local;
    }

    private Path download(URI uri, Path taskDir, int maxBytes) throws Exception {
        Path target = taskDir.resolve("source.txt");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(120))
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("下载文件失败 HTTP " + resp.statusCode());
        }
        long total = 0;
        try (InputStream in = resp.body(); OutputStream os = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("文件超过体积上限 " + maxBytes + " 字节");
                }
                os.write(buf, 0, n);
            }
        }
        if (total == 0) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("下载到的文件为空");
        }
        return target;
    }

    private static boolean isHttpUrl(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase();
        return t.startsWith("http://") || t.startsWith("https://");
    }

    private static List<String> parseFields(String raw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[;；,，、\\n\\r]+")) {
                String f = part.trim();
                if (!f.isEmpty()) set.add(f);
            }
        }
        return new ArrayList<>(set);
    }

    private static final class ChunkOut {
        final List<Extraction.Hit> hits;
        final boolean error;

        private ChunkOut(List<Extraction.Hit> hits, boolean error) {
            this.hits = hits;
            this.error = error;
        }

        static ChunkOut ok(List<Extraction.Hit> hits) { return new ChunkOut(hits, false); }
        static ChunkOut failed() { return new ChunkOut(List.of(), true); }
    }
}

package com.jtzj.agent.core.service;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.support.Extraction;
import com.jtzj.agent.core.support.QaMerge;
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
        List<String> fields = Extraction.parseFields(fieldsRaw);
        if (fields.isEmpty()) {
            return "Error: fields 为空，请指定要抽取的字段。";
        }
        try {
            Path file = resolveSource(txtSource, cfg, taskDir);
            if (file == null) {
                return "Error: 本地文档路径必须在任务工作目录内且存在：" + txtSource;
            }
            boolean downloaded = isHttpUrl(txtSource);
            long fileSize = Files.size(file);
            if (fileSize > cfg.getMaxDownloadBytes()) {
                if (downloaded) Files.deleteIfExists(file);
                return "Error: 文件体积 " + fileSize + " 字节超过上限 " + cfg.getMaxDownloadBytes();
            }
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
            long fileSize = Files.size(file);
            if (fileSize > cfg.getMaxDownloadBytes()) {
                if (downloaded) Files.deleteIfExists(file);
                return "Error: 文件体积 " + fileSize + " 字节超过上限 " + cfg.getMaxDownloadBytes();
            }
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
            // 多块：逐块问答后归并成一段。直接拼接等于把同一个问题答 N 遍，
            // 还得主模型自己汇总——那是把分批的代价推给调用方。
            List<String> parts = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                String answer = visionClient.askTextOnly(buildQaPrompt(chunks.get(i), i + 1, totalChunks, q));
                if (answer.startsWith("Error:")) {
                    failed.add("第 " + (i + 1) + "/" + totalChunks + " 段");
                } else {
                    parts.add("【第 " + (i + 1) + "/" + totalChunks + " 段】\n" + answer.trim());
                }
            }
            if (parts.isEmpty()) {
                return "Error: 全部 " + totalChunks + " 段都没能理解。";
            }
            String merged = QaMerge.merge(visionClient, parts, q);
            if (!failed.isEmpty()) {
                merged += "\n\n（未能理解的文本段：" + String.join("、", failed) + "）";
            }
            return merged;
        } catch (Exception e) {
            log.error("text understand failed src={}", txtSource, e);
            return "Error: 处理 TXT 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ---- 内部 ----

    private ChunkOut extractChunk(String text, int chunkNum, int totalChunks, List<String> fields) {
        String prompt = "这是同一份文档的第 " + chunkNum + " 段（全文共 " + totalChunks + " 段）。\n"
                + "只为下面每个字段找出它的值和值出现的段号（多个都列）。只输出一个 JSON 对象，不要解释、不要代码围栏。\n"
                + "字段名常常以别名出现在文档里，必须按语义匹配，不要因为文档里没有同名标签就判为找不到。常见对应："
                + "签订日期=合同订立时间/签署日期/签约日期/落款日期；"
                + "甲方=承包人/发包人/出让方/需方；乙方=分包人/供方/出让人/受让人；"
                + "合同金额=签约合同价款/合同总价/中标价/价款；"
                + "统一社会信用代码=纳税人识别号/信用代码；致（债务人）=致/债务人。\n"
                + "value 必须是文档里原样出现的片段：不要改写、不要补全或调整数字分隔符、不要把跨行的内容拼起来。"
                + "文档里是占位符（如 --）就照抄占位符。\n"
                + "确实找不到就不要输出该字段，禁止编造。\n"
                + "字段列表：\n" + String.join("\n", fields) + "\n\n输出格式（严格）：\n"
                + "{\"hits\":[{\"field\":\"字段名\",\"value\":\"值\",\"pages\":[段号]}]}\n\n"
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
        return Extraction.merge(mapper, fields, acc, failedChunks, totalChunks);
    }

    /**
     * 分块。OCR 文本每页前有 {@code --- 第 N 页 ---}，优先按页边界打包：绝不把一页劈开，
     * 也就不会把一个字段值劈开（按字符开窗正是之前产出 {@code 市建诊 有限公司} 这类残缺值的原因之一）。
     * 没有页标记才退回字符窗口。
     */
    private List<String> chunk(String text, int size, int overlap) {
        List<String> pages = splitByPageMarkers(text);
        if (pages.size() > 1) {
            List<String> packed = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String page : pages) {
                for (String piece : oversize(page, size)) {
                    if (current.length() > 0 && current.length() + piece.length() > size) {
                        packed.add(current.toString());
                        current.setLength(0);
                    }
                    current.append(piece);
                }
            }
            if (current.length() > 0) {
                packed.add(current.toString());
            }
            return packed;
        }
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int step = Math.max(1, size - overlap);
        for (int start = 0; start < len; start += step) {
            int end = Math.min(start + size, len);
            chunks.add(text.substring(start, end));
            if (end >= len) break;
        }
        return chunks;
    }

    /** 按页标记切页，标记本身留在每段开头（模型要靠它溯源）。少于两页返回空表。 */
    private static List<String> splitByPageMarkers(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^--- 第 \\d+ 页 ---$").matcher(text);
        List<Integer> at = new ArrayList<>();
        while (m.find()) {
            at.add(m.start());
        }
        if (at.size() < 2) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        if (at.get(0) > 0) {
            String head = text.substring(0, at.get(0));
            if (!head.isBlank()) {
                parts.add(head);
            }
        }
        for (int i = 0; i < at.size(); i++) {
            int from = at.get(i);
            int to = (i + 1 < at.size()) ? at.get(i + 1) : text.length();
            parts.add(text.substring(from, to));
        }
        return parts;
    }

    /** 单页本身就超限时只切这一页，不向相邻页借内容。 */
    private static List<String> oversize(String page, int size) {
        if (page.length() <= size) {
            return List.of(page);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < page.length(); i += size) {
            out.add(page.substring(i, Math.min(i + size, page.length())));
        }
        return out;
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

    // parseFields 统一用 Extraction.parseFields()

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

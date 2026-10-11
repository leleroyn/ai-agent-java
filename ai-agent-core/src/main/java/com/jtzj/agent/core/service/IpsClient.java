package com.jtzj.agent.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtzj.agent.core.config.AgentProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 综合服务平台（IPS）客户端，一个类管它的三个接口：
 *
 * <ul>
 *   <li>{@code POST /api/v3/ocr/preprocess}      {@link #ocr} —— 抽取类任务的统一文本通道</li>
 *   <li>{@code POST /api/v3/seal/preprocess}     {@link #seal} —— 内置工具 {@code recognize_seal}</li>
 *   <li>{@code POST /api/v3/invoice/preprocess}  {@link #invoice} —— 内置工具 {@code recognize_invoice}</li>
 * </ul>
 *
 * <p>三个接口本来就是同一台服务的同一族 API（multipart 上传文件、按魔数判类型），所以配置、
 * 基址、开关、multipart 组装全部收在一处：{@code agent.ips.*}。之前 OCR 单独一个
 * {@code agent.ocr} 块，等于同一台服务配两遍。
 *
 * <p>与 {@link VisionClient} 一致：失败返回 {@code "Error: ..."} 文本而不是抛异常，调用方才能
 * 按页失败处理或按来源汇总。注意印章/发票的<b>错误响应是纯文本不是 JSON</b>（发票检测不到时
 * 回 400 + "未检测到有效发票"），所以非 2xx 时直接把响应体当错误原因带回去。
 */
@Component
public class IpsClient {

    private static final Logger log = LoggerFactory.getLogger(IpsClient.class);
    private static final String OCR_PATH = "/api/v3/ocr/preprocess";
    private static final String SEAL_PATH = "/api/v3/seal/preprocess";
    private static final String INVOICE_PATH = "/api/v3/invoice/preprocess";

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public IpsClient(AgentProperties props,
                     @Qualifier("agentScopeObjectMapper") ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 该服务是否可用：开关打开且配了基址。三个接口共用这个判断。 */
    public boolean isEnabled() {
        return baseUrl() != null;
    }

    /** 服务基址（不带路径）；未配置返回 {@code null}。 */
    public String baseUrl() {
        AgentProperties.Ips c = props.getIps();
        if (!c.isEnabled() || c.getBaseUrl() == null || c.getBaseUrl().isBlank()) {
            return null;
        }
        String s = c.getBaseUrl().trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * 文字识别（图片或 PDF，服务端按魔数判类型）。
     *
     * <p>这里<b>一页一图</b>地调用而不是整篇 PDF 一次上传——服务端按页拼接时会丢掉空白页，
     * 页码就对不上了，而抽取结果必须带准确页码。
     *
     * @param fileBytes 图片或 PDF 二进制
     * @param filename  multipart 里的文件名（服务端按魔数判类型，名字只为可读性）
     * @return 识别文本（空串是合法结果：该页没有文字）；失败返回 {@code "Error: ..."}
     */
    public String ocr(byte[] fileBytes, String filename) {
        if (!isEnabled()) {
            return "Error: OCR 通道未启用（agent.ips.enabled=false 或未配 agent.ips.base-url）。";
        }
        AgentProperties.Ips c = props.getIps();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("format", c.getFormat() == null ? "txt" : c.getFormat().trim());
        fields.put("resize", String.valueOf(c.isResize()));
        fields.put("use_cls", String.valueOf(c.isUseCls()));
        if (c.getAppId() != null && !c.getAppId().isBlank()) {
            fields.put("app_id", c.getAppId().trim());
        }
        String body = post(OCR_PATH, fileBytes, filename, fields, c.getOcrTimeoutSeconds(), "OCR");
        if (body.startsWith("Error:")) {
            return body;
        }
        String text = extractOcrText(body);
        if (text == null) {
            log.warn("ocr response has no ocr_text body={}", snippet(body));
            return "Error: OCR 服务响应里没有 ocr_text 字段：" + snippet(body);
        }
        return text;
    }

    /**
     * 印章识别。响应是 JSON 数组，每个印章一个元素（空数组=未检出，不是错误）。
     *
     * @param returnOcrText  是否返回印章上的公司名（false 时明显更快，但只有置信度和类型）
     * @param initConfidence 检测阈值 0~1，{@code null}=用服务端默认 0.5
     * @return 成功返回响应体原文（JSON 数组文本）；失败返回 {@code "Error: ..."}
     */
    public String seal(Path file, String filename, boolean returnOcrText, Double initConfidence) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("return_ocr_text", String.valueOf(returnOcrText));
        // 固定不取印章裁剪图：base64 体积大、对文本输出无用，还会占满主模型上下文。
        fields.put("return_seal_image", "false");
        if (initConfidence != null) {
            fields.put("init_confidence", String.valueOf(initConfidence));
        }
        return post(SEAL_PATH, file, filename, fields, props.getIps().getTimeoutSeconds(), "印章");
    }

    /**
     * 发票识别。响应是<b>最多 1 个元素</b>的 JSON 数组，字段在 {@code ocr_text} 里（是个 JSON 字符串）。
     *
     * @param returnOcrText  是否返回发票内容字段（图片输入恒有值；只对文字版 PDF 生效）
     * @param initConfidence 检测阈值 0~1，{@code null}=用服务端默认 0.5
     */
    public String invoice(Path file, String filename, boolean returnOcrText, Double initConfidence) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("return_ocr_text", String.valueOf(returnOcrText));
        // 固定不取企业头像裁剪图：base64 体积大、对字段抽取无用，只会占满主模型上下文。
        fields.put("return_corp_image", "false");
        if (initConfidence != null) {
            fields.put("init_confidence", String.valueOf(initConfidence));
        }
        return post(INVOICE_PATH, file, filename, fields, props.getIps().getTimeoutSeconds(), "发票");
    }

    /** 统一的 multipart POST（内存版，给逐页 OCR 的单页图用）。 */
    private String post(String path, byte[] fileBytes, String filename,
                        Map<String, String> fields, int timeoutSeconds, String label) {
        if (fileBytes == null || fileBytes.length == 0) {
            return "Error: 未提供文件字节。";
        }
        String boundary = newBoundary();
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes.length + 512);
        try {
            writeMultipart(out, boundary, filename, new ByteArrayInputStream(fileBytes), fields);
        } catch (Exception e) {
            log.warn("{} build body failed: {}", label, e.toString());
            return "Error: 调用" + label + "服务失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        byte[] body = out.toByteArray();
        return send(path, boundary, HttpRequest.BodyPublishers.ofByteArray(body),
                body.length, filename, timeoutSeconds, label);
    }

    /**
     * 统一的 multipart POST（流式版，给整份文件用：印章、发票）。
     *
     * <p>请求体先落到来源文件旁边（同一个任务 {@code .ocr/} 沙箱），再用
     * {@link HttpRequest.BodyPublishers#ofFile} 发送：堆占用与文件大小无关，
     * Content-Length 也是准确值（不会退化成 chunked，代理侧兼容）。之前
     * {@code readAllBytes} + {@code ByteArrayOutputStream} + {@code toByteArray()}
     * 三次完整副本，一份 200MB 的 PDF 单次调用就能吃掉 600MB 堆。
     * 发完划删临时体；删不掉也只留在任务沙箱里，随任务结束一起清。
     */
    private String post(String path, Path file, String filename,
                        Map<String, String> fields, int timeoutSeconds, String label) {
        long size;
        try {
            size = Files.size(file);
        } catch (Exception e) {
            log.warn("{} stat file failed: {}", label, e.toString());
            return "Error: 读取文件失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (size == 0) {
            return "Error: 未提供文件字节。";
        }
        String boundary = newBoundary();
        Path body = file.toAbsolutePath().getParent().resolve("mp-" + System.nanoTime() + ".part");
        try {
            try (OutputStream out = Files.newOutputStream(body);
                 InputStream in = Files.newInputStream(file)) {
                writeMultipart(out, boundary, filename, in, fields);
            }
            return send(path, boundary, HttpRequest.BodyPublishers.ofFile(body),
                    size, filename, timeoutSeconds, label);
        } catch (Exception e) {
            log.warn("{} call failed: {}", label, e.toString());
            return "Error: 调用" + label + "服务失败：" + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
        } finally {
            try {
                Files.deleteIfExists(body);
            } catch (Exception ignored) {
                // 请求已经发完，删不掉就随任务沙箱一起清
            }
        }
    }

    /** 发送并处理响应：成功回响应体原文，失败回 {@code "Error: ..."}。 */
    private String send(String path, String boundary, HttpRequest.BodyPublisher publisher,
                        long fileBytes, String filename, int timeoutSeconds, String label) {
        String base = baseUrl();
        if (base == null) {
            return "Error: 识别服务未启用（agent.ips.enabled=false 或未配 agent.ips.base-url）。";
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(publisher)
                    .build();
            long start = System.nanoTime();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long ms = (System.nanoTime() - start) / 1_000_000;
            String respBody = new String(resp.body(), StandardCharsets.UTF_8);
            if (resp.statusCode() / 100 != 2) {
                log.warn("{} call HTTP {} in {}ms body={}", label, resp.statusCode(), ms, snippet(respBody));
                return "Error: " + label + "服务返回 HTTP " + resp.statusCode() + "：" + snippet(respBody);
            }
            log.info("{} ok file={} bytes={} in {}ms chars={}",
                    label, filename, fileBytes, ms, respBody.length());
            return respBody;
        } catch (Exception e) {
            log.warn("{} call failed: {}", label, e.toString());
            return "Error: 调用" + label + "服务失败：" + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
        }
    }

    private static String newBoundary() {
        return "----AgentIps" + System.nanoTime();
    }

    /** 手写 multipart/form-data 体：一个 file 段 + 若干表单字段段，不引入新依赖。 */
    private static void writeMultipart(OutputStream out, String boundary, String filename,
                                       InputStream fileStream, Map<String, String> fields) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType(filename) + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        fileStream.transferTo(out);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, String> e : fields.entrySet()) {
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                    + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }
    /** 服务端按魔数判类型，这里给对 Content-Type 只为可读性与网关兼容。 */
    private static String contentType(String filename) {
        String n = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    /** 取 {@code ocr_text}；缺失/非字符串返回 null（空串是合法结果：该页无文字）。 */
    private String extractOcrText(String respBody) {
        try {
            JsonNode root = mapper.readTree(respBody);
            JsonNode text = root.path("ocr_text");
            if (text.isMissingNode() || text.isNull()) {
                return null;
            }
            return cleanMarkdown(text.asText());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * format=md 的返回里带 {@code <img src="images/bbox_*.jpg" />} 这类占位标签：服务端并不返回
     * 这些图，留着只会白占 token、还会让抽取模型以为有图。顺手压掉多余空行。
     */
    private static String cleanMarkdown(String raw) {
        String cleaned = raw.replaceAll("(?i)<img[^>]*/?>", "")
                .replaceAll("(?m)[ \t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return cleaned;
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
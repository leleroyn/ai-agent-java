package com.jtzj.agent.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtzj.agent.core.config.AgentProperties;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 独立 OCR 服务（综合服务平台 {@code POST /api/v3/ocr/preprocess}）客户端。
 *
 * <p>存在的意义是<b>省时间</b>：扫描版 PDF 走视觉模型（VL）逐批看图很慢，OCR 服务专做文字识别、
 * 单页更快更稳。{@link PdfService} 挑出需要 OCR 的页——含图片的页（扫描件每页基本就是一整张位图），以及
 * 文字层里几乎没有中日韩文字的页（缺 ToUnicode 的假文字层）——栅格化后送这里拿文本，其余页直接取文字层，
 * 之后的抽取/问答退化成纯文本调用。
 *
 * <p>接口是 multipart/form-data：{@code file}（图片或 PDF 二进制）+ 可选 {@code app_id}
 * {@code resize} {@code use_cls} {@code format}；响应 {@code {"ocr_text": "..."}}。
 * 这里<b>一页一图</b>地调用而不是整篇 PDF 一次上传——服务端按页拼接时会丢掉空白页，
 * 页码就对不上了，而抽取结果必须带准确页码。
 *
 * <p>与 {@link VisionClient} 一致：失败返回 {@code "Error: ..."} 文本而不是抛异常，调用方才能
 * 按页失败处理或整体回退视觉管线。
 */
@Component
public class OcrClient {

    private static final Logger log = LoggerFactory.getLogger(OcrClient.class);
    private static final String PATH = "/api/v3/ocr/preprocess";

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OcrClient(AgentProperties props,
                     @Qualifier("agentScopeObjectMapper") ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** OCR 通道是否可用：开关打开且配了服务地址。 */
    public boolean isEnabled() {
        AgentProperties.Ocr o = props.getOcr();
        return o.isEnabled() && o.getBaseUrl() != null && !o.getBaseUrl().isBlank();
    }

    /**
     * 识别一张页图。
     *
     * @param imageBytes JPEG/PNG 等图片字节
     * @param filename   multipart 里的文件名（服务端按魔数判类型，名字只为可读性）
     * @return 识别文本（可能是空串=该页没有文字）；失败返回 {@code "Error: ..."}
     */
    public String ocrImage(byte[] imageBytes, String filename) {
        if (!isEnabled()) {
            return "Error: OCR 通道未启用（agent.ocr.enabled=false 或未配 agent.ocr.base-url）。";
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return "Error: 未提供图片字节。";
        }
        AgentProperties.Ocr o = props.getOcr();
        String boundary = "----AgentOcr" + System.nanoTime();
        try {
            byte[] body = multipartBody(boundary, imageBytes, filename, o);
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimSlash(o.getBaseUrl()) + PATH))
                    .timeout(Duration.ofSeconds(o.getTimeoutSeconds()))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            long start = System.nanoTime();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long ms = (System.nanoTime() - start) / 1_000_000;
            String respBody = new String(resp.body(), StandardCharsets.UTF_8);
            if (resp.statusCode() / 100 != 2) {
                log.warn("ocr call HTTP {} in {}ms body={}", resp.statusCode(), ms, snippet(respBody));
                return "Error: OCR 服务返回 HTTP " + resp.statusCode() + "：" + snippet(respBody);
            }
            String text = extractOcrText(respBody);
            if (text == null) {
                log.warn("ocr response has no ocr_text in {}ms body={}", ms, snippet(respBody));
                return "Error: OCR 服务响应里没有 ocr_text 字段：" + snippet(respBody);
            }
            log.info("ocr ok file={} in {}ms chars={}", filename, ms, text.length());
            return text;
        } catch (Exception e) {
            log.warn("ocr call failed: {}", e.toString());
            return "Error: 调用 OCR 服务失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 手写 multipart/form-data 体：一个 file 段 + 若干表单字段段，不引入新依赖。 */
    private byte[] multipartBody(String boundary, byte[] fileBytes, String filename,
                                 AgentProperties.Ocr o) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes.length + 512);
        byte[] dash = ("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(dash);
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        writeField(out, boundary, "format", o.getFormat() == null ? "txt" : o.getFormat().trim());
        writeField(out, boundary, "resize", String.valueOf(o.isResize()));
        writeField(out, boundary, "use_cls", String.valueOf(o.isUseCls()));
        if (o.getAppId() != null && !o.getAppId().isBlank()) {
            writeField(out, boundary, "app_id", o.getAppId().trim());
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws Exception {
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /** 取 {@code ocr_text}；缺失/非字符串返回 null（空串是合法结果：该页无文字）。 */
    private String extractOcrText(String respBody) throws Exception {
        JsonNode root = mapper.readTree(respBody);
        JsonNode text = root.path("ocr_text");
        if (text.isMissingNode() || text.isNull()) {
            return null;
        }
        return text.asText();
    }

    private static String trimSlash(String url) {
        String s = url.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}

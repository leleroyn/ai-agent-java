package com.example.agent.agent;

import com.example.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 把一组图片 URL 交给独立的视觉模型做一次「理解」，只返回文字。
 *
 * <p>这条链路的存在意义是<b>解耦</b>：图片字节只发到 {@code agent.vision} 配置的 VL 端点，
 * 绝不进入主模型的对话上下文。主模型只看到本方法返回的文字结论。
 *
 * <p>用 JDK {@link HttpClient} + 已有的 Jackson 2 mapper（{@code agentScopeObjectMapper}），
 * 不引入任何新依赖。多图通过在同一条 user 消息的 content 数组里放多个 {@code image_url}
 * block 实现——实测该网关支持单请求多图，并会跨图推理。
 *
 * <p>失败一律返回 {@code "Error: ..."} 文本而不是抛异常，让调用方（工具）把可读错误交回模型，
 * 它才有机会换 URL 或换问法重试。
 */
@Component
public class VisionClient {

    private static final Logger log = LoggerFactory.getLogger(VisionClient.class);

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public VisionClient(AgentProperties props,
                        @Qualifier("agentScopeObjectMapper") ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * @param imageUrls 一个或多个图片 URL（http/https）
     * @param question  要视觉模型回答的理解性问题
     * @return 视觉模型的文字结论；任何一步失败则返回 {@code "Error: ..."} 说明
     */
    public String understand(List<String> imageUrls, String question) {
        AgentProperties.Vision v = props.getVision();
        if (!v.isEnabled()) {
            return "Error: 图片理解功能未启用（agent.vision.enabled=false）。";
        }
        if (imageUrls == null || imageUrls.isEmpty()) {
            return "Error: 未提供任何图片 URL。";
        }
        if (question == null || question.isBlank()) {
            return "Error: question 不能为空，请说明要对图片做什么。";
        }
        if (imageUrls.size() > v.getMaxImages()) {
            return "Error: 一次最多理解 " + v.getMaxImages() + " 张图，当前传入 "
                    + imageUrls.size() + " 张。请减少图片数量。";
        }

        List<ImageData> images = new ArrayList<>(imageUrls.size());
        for (String url : imageUrls) {
            ImageData data = downloadAsDataUrl(url, v);
            if (data.error != null) {
                return data.error;
            }
            images.add(data);
        }

        try {
            byte[] body = buildRequest(v, question, images);
            Duration timeout = Duration.ofSeconds(v.getTimeoutSeconds());
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimSlash(v.getBaseUrl()) + "/chat/completions"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (v.getApiKey() == null ? "" : v.getApiKey()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            long start = System.nanoTime();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long ms = (System.nanoTime() - start) / 1_000_000;
            String respBody = new String(resp.body(), StandardCharsets.UTF_8);

            if (resp.statusCode() / 100 != 2) {
                log.warn("vision call HTTP {} in {}ms body={}", resp.statusCode(), ms, snippet(respBody));
                return "Error: 视觉模型返回 HTTP " + resp.statusCode() + "：" + snippet(respBody)
                        + "（若提示 mmproj，说明该端点未加载多模态投影器，不是真 VL 模型）。";
            }
            String text = extractContent(respBody);
            if (text == null || text.isBlank()) {
                log.warn("vision returned empty content in {}ms body={}", ms, snippet(respBody));
                return "Error: 视觉模型返回了空内容。可换一种问法或减少图片数量后重试。";
            }
            log.info("vision understood images={} in {}ms chars={}", images.size(), ms, text.length());
            return text;
        } catch (Exception e) {
            log.error("vision call failed", e);
            return "Error: 调用视觉模型失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * 直接用已经准备好的图片 data URL 调用视觉模型（不再下载）。供 {@code understand_document}
     * 把 PDF 逐页栅格化后的图像喂给视觉模型用。返回文字结论，失败返回 {@code "Error: ..."}。
     */
    public String extractFromDataUrls(List<String> dataUrls, String prompt) {
        AgentProperties.Vision v = props.getVision();
        if (!v.isEnabled()) {
            return "Error: 视觉功能未启用（agent.vision.enabled=false）。";
        }
        if (dataUrls == null || dataUrls.isEmpty()) {
            return "Error: 未提供任何图片。";
        }
        try {
            List<ImageData> images = new ArrayList<>(dataUrls.size());
            for (String d : dataUrls) {
                images.add(ImageData.ok(d));
            }
            byte[] body = buildRequest(v, prompt, images);
            Duration timeout = Duration.ofSeconds(v.getTimeoutSeconds());
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimSlash(v.getBaseUrl()) + "/chat/completions"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (v.getApiKey() == null ? "" : v.getApiKey()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            long start = System.nanoTime();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long ms = (System.nanoTime() - start) / 1_000_000;
            String respBody = new String(resp.body(), StandardCharsets.UTF_8);
            if (resp.statusCode() / 100 != 2) {
                log.warn("vision(dataUrl) HTTP {} in {}ms body={}", resp.statusCode(), ms, snippet(respBody));
                return "Error: 视觉模型返回 HTTP " + resp.statusCode() + "：" + snippet(respBody);
            }
            String text = extractContent(respBody);
            if (text == null || text.isBlank()) {
                return "Error: 视觉模型返回了空内容。";
            }
            log.info("vision(dataUrl) ok images={} in {}ms chars={}", images.size(), ms, text.length());
            return text;
        } catch (Exception e) {
            log.error("vision(dataUrl) call failed", e);
            return "Error: 调用视觉模型失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 下载单张图并转成 data URL；任何不合规情况通过 {@link ImageData#error} 返回可读错误。 */
    private ImageData downloadAsDataUrl(String url, AgentProperties.Vision v) {
        if (url == null || url.isBlank()) {
            return ImageData.fail("Error: 图片 URL 为空。");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return ImageData.fail("Error: 非法的图片 URL '" + url + "'。");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return ImageData.fail("Error: 图片 URL 必须是 http/https，收到 '" + url + "'。");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(v.getTimeoutSeconds()))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                return ImageData.fail("Error: 下载图片失败 HTTP " + resp.statusCode() + "：" + url);
            }
            return dataUrlFromBytes(resp.body(),
                    resp.headers().firstValue("content-type").orElse(""), url, v);
        } catch (Exception e) {
            return ImageData.fail("Error: 下载图片异常：" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + "（" + url + "）");
        }
    }

    /**
     * 把图片 URL 下载成 data URL。成功返回 {@code data:...}，失败返回以 {@code "Error: "} 开头的可读说明。
     * 供工具层在「URL 或本地文件」混用时统一成 data URL。
     */
    public String dataUrlFromUrl(String url) {
        ImageData d = downloadAsDataUrl(url, props.getVision());
        return d.error != null ? d.error : d.dataUrl;
    }

    /**
     * 读本地图片文件成 data URL。<b>调用方必须已确保路径在任务沙箱内</b>（沙箱校验属于工具层）。
     * 成功返回 {@code data:...}，失败返回以 {@code "Error: "} 开头的说明。
     */
    public String dataUrlFromLocalFile(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (Exception e) {
            return "Error: 读取本地图片失败：" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + "（" + file + "）";
        }
        ImageData d = dataUrlFromBytes(bytes, "", file.toString(), props.getVision());
        return d.error != null ? d.error : d.dataUrl;
    }

    /** 字节→data URL 的公共校验：非空、体积上限、魔数判 MIME、base64。{@code errLabel} 用于错误里指代来源。 */
    private ImageData dataUrlFromBytes(byte[] bytes, String ctHeader, String errLabel,
                                       AgentProperties.Vision v) {
        if (bytes == null || bytes.length == 0) {
            return ImageData.fail("Error: 图片为空：" + errLabel);
        }
        if (bytes.length > v.getMaxImageBytes()) {
            return ImageData.fail("Error: 图片过大（" + bytes.length + " 字节），超过上限 "
                    + v.getMaxImageBytes() + " 字节：" + errLabel);
        }
        String mime = detectMime(bytes, ctHeader);
        if (mime == null) {
            return ImageData.fail("Error: 不是受支持的图片（PNG/JPEG/GIF/WEBP）：" + errLabel);
        }
        return ImageData.ok("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
    }

    /** 以魔数为准判定 MIME（content-type 头可能被服务端乱给）；不支持的返回 null。 */
    private static String detectMime(byte[] b, String contentTypeHeader) {
        if (startsWith(b, 0x89, 0x50, 0x4E, 0x47)) return "image/png";
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (startsWith(b, 0x47, 0x49, 0x46, 0x38)) return "image/gif";
        if (b.length >= 12 && startsWith(b, 0x52, 0x49, 0x46, 0x46)
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        // 魔数没认出来时，退而信 content-type（有些格式魔数不唯一）。
        if (contentTypeHeader != null) {
            String ct = contentTypeHeader.toLowerCase();
            if (ct.startsWith("image/png")) return "image/png";
            if (ct.startsWith("image/jpeg") || ct.startsWith("image/jpg")) return "image/jpeg";
            if (ct.startsWith("image/gif")) return "image/gif";
            if (ct.startsWith("image/webp")) return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if ((b[i] & 0xFF) != sig[i]) return false;
        }
        return true;
    }

    private byte[] buildRequest(AgentProperties.Vision v, String question, List<ImageData> images)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", v.getName());
        if (v.getTemperature() != null) {
            root.put("temperature", v.getTemperature());
        }
        // 视觉推理强度：非空则按 OpenAI reasoning_effort 下发；"none" 时额外补
        // chat_template_kwargs.enable_thinking=false——llama.cpp 不认 reasoning_effort，
        // Qwen3 关思考要靠该 kwargs（与主模型 ModelFactory 同一做法）；后端不支持时会被忽略。
        String effort = v.getReasoningEffort();
        if (effort != null && !effort.isBlank()) {
            root.put("reasoning_effort", effort.trim().toLowerCase(java.util.Locale.ROOT));
            if ("none".equalsIgnoreCase(effort.trim())) {
                root.putObject("chat_template_kwargs").put("enable_thinking", false);
            }
        }
        ArrayNode messages = root.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        content.addObject().put("type", "text").put("text", question);
        for (ImageData img : images) {
            ObjectNode block = content.addObject();
            block.put("type", "image_url");
            block.putObject("image_url").put("url", img.dataUrl);
        }
        return mapper.writeValueAsBytes(root);
    }

    /** 从 OpenAI 兼容响应里取 choices[0].message.content，兼容 string 与 [{type:text}] 两种形态。 */
    private String extractContent(String respBody) throws Exception {
        JsonNode root = mapper.readTree(respBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.hasNonNull("text")) {
                    sb.append(part.get("text").asText());
                }
            }
            return sb.toString();
        }
        return null;
    }

    private static String trimSlash(String url) {
        if (url == null) return "";
        String s = url.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String snippet(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }

    /** 下载结果：要么带 dataUrl，要么带 error，二者其一。 */
    private static final class ImageData {
        final String dataUrl;
        final String error;

        private ImageData(String dataUrl, String error) {
            this.dataUrl = dataUrl;
            this.error = error;
        }

        static ImageData ok(String dataUrl) {
            return new ImageData(dataUrl, null);
        }

        static ImageData fail(String error) {
            return new ImageData(null, error);
        }
    }
}

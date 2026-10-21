package com.jtzj.agent.core.service;

import com.jtzj.agent.core.config.AgentProperties;
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
                // 强制 HTTP/1.1：默认 HTTP_2 会在明文 http 上先发 h2c 升级头（Upgrade: h2c + HTTP2-Settings），
                // uvicorn 系服务不认，会把请求当非法/空 body 处理（422 file missing、400 Invalid HTTP request received）。
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 直接用已经准备好的图片 data URL 调用视觉模型（不再下载）。供 {@code understand_document}
     * 把 PDF 逐页栅格化后的图像喂给视觉模型用。返回文字结论，失败返回 {@code "Error: ..."}。
     */
    public String extractFromDataUrls(List<String> dataUrls, String prompt) {
        AgentProperties.Vision v = props.getVision();
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

    /**
     * 纯文本问答（无图片）：抽取 / QA 这类"OCR 之后手上只剩文本"的场景。
     *
     * <p>默认走 {@code agent.text-model} 指定的文本模型，不再复用视觉端点——实测同一份营业执照
     * OCR 文本，视觉端点在 6 字段时稳定漏掉 1 个，文本模型全对。未配置该 profile 时回退视觉端点。
     *
     * <p>发的 content 是数组形式 {@code [{"type":"text","text":"..."}]}（与带图时统一结构）。
     * llama.cpp / vLLM / OpenAI 均支持；若后端只接受纯字符串 content，需在此处分支。
     */
    public String askTextOnly(String prompt) {
        AgentProperties.Model m = props.resolveTextModel();
        if (m != null) {
            return askTextOnly(prompt, m);
        }
        AgentProperties.Vision v = props.getVision();
        return postText("text:" + v.getName(), v.getBaseUrl(), v.getApiKey(), v.getName(),
                v.getMaxTokens(), v.getTemperature(), v.getReasoningEffort(),
                v.getTimeoutSeconds(), prompt);
    }

    /** 纯文本问答，走指定模型 profile（{@code agent.models.*}）。 */
    public String askTextOnly(String prompt, AgentProperties.Model m) {
        return postText("text:" + m.getName(), m.getBaseUrl(), m.getApiKey(), m.getName(),
                m.getMaxTokens(), m.getTemperature(), m.getReasoningEffort(),
                props.getVision().getTimeoutSeconds(), prompt);
    }

    /** 纯文本请求的公共实现：建请求、发一次、取 content。{@code label} 只用于日志。 */
    private String postText(String label, String baseUrl, String apiKey, String model,
                            Integer maxTokens, Double temperature, String reasoningEffort,
                            int timeoutSeconds, String prompt) {
        try {
            byte[] body = buildRequest(model, maxTokens, temperature, reasoningEffort, prompt, List.of());
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            long start = System.nanoTime();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long ms = (System.nanoTime() - start) / 1_000_000;
            String respBody = new String(resp.body(), StandardCharsets.UTF_8);
            if (resp.statusCode() / 100 != 2) {
                log.warn("{} HTTP {} in {}ms body={}", label, resp.statusCode(), ms, snippet(respBody));
                return "Error: 模型返回 HTTP " + resp.statusCode() + "：" + snippet(respBody);
            }
            String text = extractContent(respBody);
            if (text == null || text.isBlank()) {
                return "Error: 模型返回了空内容。";
            }
            log.info("{} ok in {}ms chars={}", label, ms, text.length());
            return text;
        } catch (Exception e) {
            log.error("{} call failed", label, e);
            return "Error: 调用模型失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
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
        return buildRequest(v.getName(), v.getMaxTokens(), v.getTemperature(),
                v.getReasoningEffort(), question, images);
    }

    private byte[] buildRequest(String model, Integer maxTokens, Double temperature,
                                String reasoningEffort, String question, List<ImageData> images)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens);
        }
        if (temperature != null) {
            root.put("temperature", temperature);
        }
        // 视觉推理强度：非空则按 OpenAI reasoning_effort 下发；"none" 时额外补
        // chat_template_kwargs.enable_thinking=false——llama.cpp 不认 reasoning_effort，
        // Qwen3 关思考要靠该 kwargs（与主模型 ModelFactory 同一做法）；后端不支持时会被忽略。
        String effort = reasoningEffort;
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
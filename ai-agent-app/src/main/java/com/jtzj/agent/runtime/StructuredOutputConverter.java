package com.jtzj.agent.runtime;

import com.jtzj.agent.core.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 两阶段结构化输出的第二阶段：把一段自由文本（通常已完成工具调用的结果）用主模型的
 * {@code response_format: json_schema} 转成严格 JSON。
 *
 * <p>关键点：本请求<b>不带 tools</b>。实测 llama.cpp 在只有 {@code response_format} 而无 tools 时
 * 能干净地按 schema 输出 JSON；一旦 tools 与 {@code response_format} 同时下发，模型会被语法约束
 * 强制直接作答、抑制工具调用。因此工具轮与结构化轮必须分开——本类只负责结构化轮。
 *
 * <p>输出预算取 profile 的 {@code structured-max-tokens}（留空沿用 {@code max-tokens}），代码侧
 * 不再有写死的 2048：输出被截断时 strata 端点回 HTTP 502 {@code structured_output_failed}，
 * llama.cpp 回 HTTP 200 带半截 JSON（{@code finish_reason=length}），两种在这里都变成 null。
 *
 * <p>HTTP 超时由调用方按任务剩余墙钟传入（大 schema 的转换实测会跑几十秒到几分钟，固定 120 秒
 * 会把能成的任务判成失败）。
 *
 * <p>复用 JDK {@link HttpClient} + 已有的 Jackson 2 mapper，不引入新依赖。失败返回 {@code null}，
 * 由调用方（{@link AgentTaskRunner}）决定如何报错。
 */
@Component
public class StructuredOutputConverter {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputConverter.class);

    /** response_format.json_schema.name 须匹配 ^[a-zA-Z0-9_-]{1,64}$。 */
    private static final String SCHEMA_NAME = "structured_output";
    /** structured-max-tokens 与 max-tokens 都没配时的兜底输出预算。 */
    private static final int MAX_OUTPUT_TOKENS = 2048;
    /** 调用方没给预算时的兜底超时（即历史写死值）。 */
    private static final Duration DEFAULT_PHASE_TIMEOUT = Duration.ofSeconds(120);

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public StructuredOutputConverter(AgentProperties props,
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
     * @param text    自由文本结果（第一阶段产物）
     * @param schema  调用方 JSON Schema
     * @param timeout 本次调用的墙钟预算（任务剩余时间）；null/非正数→{@link #DEFAULT_PHASE_TIMEOUT}
     * @return 解析后的 JSON（object/array）；端点异常、空返回或非 JSON 时返回 {@code null}
     */
    public JsonNode toStructured(String text, JsonNode schema, String modelName, Duration timeout) {
        if (text == null || text.isBlank() || schema == null) {
            return null;
        }
        AgentProperties.Model m = props.resolveModel(modelName);
        Duration httpTimeout = (timeout == null || timeout.isNegative() || timeout.isZero())
                ? DEFAULT_PHASE_TIMEOUT : timeout;
        log.debug("structured convert max_tokens={} http_timeout={}s text_chars={}",
                effectiveMaxTokens(m), httpTimeout.toSeconds(), text.length());
        try {
            byte[] body = buildRequest(m, text, schema);
            HttpRequest req = HttpRequest
                    .newBuilder(URI.create(trimSlash(m.getBaseUrl()) + "/chat/completions"))
                    .timeout(httpTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (m.getApiKey() == null ? "" : m.getApiKey()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            long start = System.nanoTime();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long ms = (System.nanoTime() - start) / 1_000_000;
            String respBody = new String(resp.body(), StandardCharsets.UTF_8);

            if (resp.statusCode() / 100 != 2) {
                log.warn("structured convert HTTP {} in {}ms max_tokens={} body={}",
                        resp.statusCode(), ms, effectiveMaxTokens(m), snippet(respBody));
                return null;
            }
            String content = extractContent(respBody);
            if (content == null || content.isBlank()) {
                log.warn("structured convert empty content in {}ms max_tokens={}", ms, effectiveMaxTokens(m));
                return null;
            }
            JsonNode node = mapper.readTree(stripCodeFence(content.trim()));
            if (node.isObject() || node.isArray()) {
                log.info("structured convert ok in {}ms max_tokens={} content_len={}",
                        ms, effectiveMaxTokens(m), content.length());
                return node;
            }
            log.warn("structured convert produced non-JSON content in {}ms max_tokens={} content_len={}",
                    ms, effectiveMaxTokens(m), content.length());
            return null;
        } catch (Exception e) {
            log.error("structured convert call failed", e);
            return null;
        }
    }

    /**
     * 第二阶段的输出预算：优先 {@code structured-max-tokens}，留空沿用 profile 的 {@code max-tokens}，
     * 两者都没有才兜底 {@link #MAX_OUTPUT_TOKENS}。flash 与 pro 同一算法，不再有 2048 写死上限。
     */
    public static int effectiveMaxTokens(AgentProperties.Model m) {
        Integer configured = m.getStructuredMaxTokens() != null ? m.getStructuredMaxTokens() : m.getMaxTokens();
        return configured != null ? configured : MAX_OUTPUT_TOKENS;
    }

    private byte[] buildRequest(AgentProperties.Model m, String text, JsonNode schema) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", m.getName());
        root.put("temperature", 0.0);
        int maxTokens = effectiveMaxTokens(m);
        root.put("max_tokens", maxTokens);

        ObjectNode fmt = root.putObject("response_format");
        fmt.put("type", "json_schema");
        ObjectNode js = fmt.putObject("json_schema");
        js.put("name", SCHEMA_NAME);
        js.put("strict", true);
        js.set("schema", schema);

        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", "把下面的【结果文本】转换成严格符合【JSON Schema】的 JSON，"
                + "只输出 JSON 本身，不要任何解释、前后缀或代码围栏。文本里没有的信息按 schema 允许的方式留空，"
                + "不要编造。\n\n【JSON Schema】\n" + schema + "\n\n【结果文本】\n" + text);
        return mapper.writeValueAsBytes(root);
    }

    /** 从 OpenAI 兼容响应取 choices[0].message.content，兼容 string 与 [{type:text}]。 */
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

    private static String stripCodeFence(String text) {
        String t = text.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int nl = t.indexOf('\n');
        if (nl < 0) {
            return t;
        }
        String body = t.substring(nl + 1);
        int close = body.lastIndexOf("```");
        return close >= 0 ? body.substring(0, close) : body;
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}

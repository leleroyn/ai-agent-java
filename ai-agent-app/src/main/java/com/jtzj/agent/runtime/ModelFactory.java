package com.jtzj.agent.runtime;

import com.jtzj.agent.core.config.AgentProperties;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the OpenAI-compatible chat model against the configured endpoint.
 *
 * <p>Two settings here are load-bearing and were established by probing the endpoint:
 * <ul>
 *   <li>{@code nativeStructuredOutputWithTools(false)} — the endpoint rejects
 *       {@code tools} + {@code response_format:json_schema} in one request with
 *       HTTP 400 "failed to initialize samplers: failed to parse grammar".</li>
 *   <li>{@code reasoningEffort("none")} — with reasoning on, the model consumes the whole
 *       token budget on {@code reasoning_content} and returns empty content
 *       ({@code finish_reason=length}).</li>
 * </ul>
 */
@Component
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private final AgentProperties props;

    public ModelFactory(AgentProperties props) {
        this.props = props;
    }

    /**
     * 按所选 profile 建模型（每个任务一个、互不干扰）。{@code modelName} 为空→用默认 profile。
     */
    public OpenAIChatModel build(String modelName) {
        AgentProperties.Model m = props.resolveModel(modelName);

        // 按结构化输出策略解析原生开关；TWO_PHASE/OFF 下 schema 不传给 agent，withTools 无关，置 false 保险。
        boolean nativeWithTools;
        switch (m.getStructuredOutputMode()) {
            case NATIVE -> nativeWithTools = true;
            case TOOL, TWO_PHASE, OFF -> nativeWithTools = false;
            default -> nativeWithTools = m.isNativeStructuredOutputWithTools(); // AUTO: 旧行为
        }

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .baseUrl(m.getBaseUrl())
                .apiKey(m.getApiKey())
                .modelName(m.getName())
                // 本应用只 block() 取最终 Msg，不需要流式；框架默认 stream=true 会逐块打 SSE 日志、
                // 且每个模型调用走一次长连接。显式关掉：单次非流式请求，日志干净。（builder 字段在
                // merge 时优先于 GenerateOptions，所以必须在这里设，光改 generateOptions 无效。）
                .stream(false)
                .nativeStructuredOutput(m.isNativeStructuredOutput())
                .nativeStructuredOutputWithTools(nativeWithTools);

        if (m.getContextWindowSize() != null && m.getContextWindowSize() > 0) {
            builder.contextWindowSize(m.getContextWindowSize());
        }

        GenerateOptions.Builder go = GenerateOptions.builder();
        if (m.getMaxTokens() != null) {
            go.maxTokens(m.getMaxTokens());
        }
        if (m.getTemperature() != null) {
            go.temperature(m.getTemperature());
        }
        String effort = m.getReasoningEffort();
        if (effort != null && !effort.isBlank()) {
            go.reasoningEffort(effort.trim().toLowerCase(Locale.ROOT));
        }
        // llama.cpp 的 OpenAI 接口不认 OpenAI 的 reasoning_effort 参数：Qwen3 关思考必须走
        // 顶层 chat_template_kwargs:{"enable_thinking":false}。与 reasoning-effort=none 联动——
        // effort=none 时额外发该参数（对不认此参数的后端无害，会被忽略）。
        if (effort != null && "none".equalsIgnoreCase(effort.trim())) {
            Map<String, Object> kwargs = new LinkedHashMap<>();
            kwargs.put("enable_thinking", false);
            go.additionalBodyParam("chat_template_kwargs", kwargs);
        }
        builder.generateOptions(go.build());

        log.debug("building model name={} baseUrl={} nativeSoWithTools={} reasoningEffort={}",
                m.getName(), m.getBaseUrl(), m.isNativeStructuredOutputWithTools(), effort);

        return builder.build();
    }
}

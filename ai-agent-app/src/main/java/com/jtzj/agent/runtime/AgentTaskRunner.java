package com.jtzj.agent.runtime;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.model.ToolCallRecord;
import com.jtzj.agent.core.support.ToolTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import com.jtzj.agent.core.error.AgentException;
import com.jtzj.agent.core.error.ErrorCodes;

/**
 * Runs one instruction through a freshly built {@link ReActAgent} and returns the result.
 *
 * <p>One agent instance per task, by design: each task carries its own output schema,
 * session id and permission context, and a shared instance would leak conversation state
 * between unrelated callers.
 */
@Component
public class AgentTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskRunner.class);

    /** Metadata key AgentScope stores parsed structured output under. */
    private static final String STRUCTURED_OUTPUT_KEY = "_structured_output";

    /**
     * An instruction that names a document file. Such a task must be answered from tool output:
     * measured once, the model answered a business-license image task in 1.6s with no tool call at
     * all and invented another company's fields.
     */
    private static final Pattern DOCUMENT_REFERENCE = Pattern.compile(
            "(?i)https?://\\S*\\.(?:pdf|jpe?g|png|webp|bmp|gif|txt|docx?|xlsx?|pptx?)(?![0-9A-Za-z])");

    /** two_phase 第二阶段的地板超时：任务预算已耗尽时也给足这么多秒做转换（见 {@link #phaseTimeout}）。 */
    private static final long MIN_PHASE_TIMEOUT_SECONDS = 60;

    private final AgentProperties props;
    private final ModelFactory modelFactory;
    private final ToolkitFactory toolkitFactory;
    private final TaskWorkspaceFactory workspaceFactory;
    private final SourceIngestor sourceIngestor;
    private final SkillRepositoryFactory skillRepositoryFactory;
    private final StructuredOutputConverter structuredConverter;
    private final ObjectMapper mapper;

    public AgentTaskRunner(AgentProperties props, ModelFactory modelFactory,
                           ToolkitFactory toolkitFactory, TaskWorkspaceFactory workspaceFactory,
                           SourceIngestor sourceIngestor,
                           SkillRepositoryFactory skillRepositoryFactory,
                           StructuredOutputConverter structuredConverter,
                           ObjectMapper mapper) {
        this.props = props;
        this.modelFactory = modelFactory;
        this.toolkitFactory = toolkitFactory;
        this.workspaceFactory = workspaceFactory;
        this.sourceIngestor = sourceIngestor;
        this.skillRepositoryFactory = skillRepositoryFactory;
        this.structuredConverter = structuredConverter;
        this.mapper = mapper;
    }

    /**
     * @param result      structured JSON when the task carried an outputSchema, else null
     * @param text        final assistant text (always best-effort)
     * @param usage       token usage, may be null when the provider reports none
     * @param workspace   the task's private directory, where any produced files live
     * @param toolCalls   这趟运行的工具调用链（一条调用一个元素）；空集合 = 一次都没调
     */
    public record RunOutcome(JsonNode result, String text, ChatUsage usage, Path workspace,
                             List<ToolCallRecord> toolCalls) {
    }

    /**
     * Execute one task to completion.
     *
     * @param taskId      used to derive a per-task session id
     * @param instruction natural-language instruction
     * @param schema      caller JSON Schema, or null for free-text output
     * @param skills      skill names to expose, or empty for every installed skill
     * @param modelName   main-model profile to use (flash / pro); null/blank = default
     * @param budget      wall-clock limit including tool execution
     * @param sync        调用方是否在阻塞等结果；true 时长 PDF 在落盘时就裁成首尾各 5 页
     * @throws AgentException on timeout, interruption, or agent failure
     */
    public RunOutcome run(String taskId, String instruction, JsonNode schema,
                          List<String> skills, Duration budget, String modelName, boolean sync) {
        Path taskDir = workspaceFactory.create(taskId);
        // 调用链从任务一开始就记：来源下载失败也要留下链，失败的任务同样要落库。
        ToolTrace trace = ToolTrace.of(props.getTrace().isEnabled(),
                props.getTrace().getMaxEntries(), props.getTrace().getMaxArgsChars());
        try {
            // 指令里的文件 URL 先抓成 src/ 下的本地文件，并把指令里的 URL 换成相对路径：
            // 工具只吃本地路径，同一份 30MB 扫描件不会在一条任务里被下载好几遍。
            String rewritten = sourceIngestor.ingest(instruction, taskDir, sync).instruction();
            return runInWorkspace(taskId, instruction, rewritten, schema, skills, budget,
                    modelName, taskDir, trace);
        } catch (AgentException e) {
            // 失败也要带链：「断在哪一次工具调用」常常就是根因，TaskPoller 把它写进 agent_tool_call。
            if (e.getToolCalls() == null) {
                e.setToolCalls(trace.records());
            }
            throw e;
        } finally {
            // 唯一的清理开关：true 时整个任务目录（含 src/、.ocr/、.ingest/）一起删，
            // 否则每份扫描件都在盘上留几十 MB；false 时一律保留，测试环境靠它事后排查。
            if (props.getTools().isDeleteWorkspaceOnFinish()) {
                workspaceFactory.delete(taskId);
            }
        }
    }

    /**
     * @param instruction 调用方原始指令，只用于「这条指令提到了文件」的判定
     * @param rewritten   把文件 URL 换成本地路径后的指令，真正喂给模型
     */
    private RunOutcome runInWorkspace(String taskId, String instruction, String rewritten,
                                      JsonNode schema, List<String> skills, Duration budget,
                                      String modelName, Path taskDir, ToolTrace trace) {
        ReActAgent agent = newAgent(taskDir, skills, modelName, trace, budget);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("task-" + taskId)
                .userId("agent-service")
                .build();

        // Skills the caller named explicitly are injected here instead of being left to the
        // model's discretion; see SkillRepositoryFactory.inlineFor.
        String skillBlock = skillRepositoryFactory.inlineFor(skills);
        String prompt = skillBlock.isEmpty()
                ? rewritten
                : "已加载的技能规范（必须严格遵循，不要再调用 load_skill_through_path 加载它们）：\n\n"
                        + skillBlock + "\n任务指令：\n" + rewritten;

        AgentProperties.StructuredOutputMode mode = props.resolveModel(modelName).getStructuredOutputMode();
        // TWO_PHASE / OFF：第一趟不带 schema 跑，让 understand_document 等工具能正常调用
        // （带 schema 时 llama.cpp 会因 response_format 语法约束抑制工具调用）。TWO_PHASE 再补
        // 一次不带 tools 的文本→JSON 结构化调用；OFF 只兜底解析回复文本里的 JSON。
        boolean freeFormFirst = schema != null
                && (mode == AgentProperties.StructuredOutputMode.TWO_PHASE
                        || mode == AgentProperties.StructuredOutputMode.OFF);

        long startedAt = System.nanoTime();
        Msg reply = await(taskId, call(agent, prompt, schema, freeFormFirst, ctx), budget);
        if (reply == null) {
            throw new AgentException(ErrorCodes.NO_RESULT, "agent returned no message", true);
        }
        if (ungroundedAnswer(instruction, trace)) {
            reply = answerGroundedInTools(taskId, instruction, taskDir, skills, modelName, prompt, schema,
                    freeFormFirst, ctx, trace, budget, startedAt);
            if (reply == null) {
                throw new AgentException(ErrorCodes.NO_RESULT,
                        "document instruction answered without any tool call (guard disabled)", false);
            }
        }

        String text = safeText(reply);
        JsonNode result;
        if (mode == AgentProperties.StructuredOutputMode.TWO_PHASE && schema != null) {
            result = structuredConverter.toStructured(text, schema, modelName, phaseTimeout(budget, startedAt));
        } else {
            result = extractStructured(reply, schema);
        }
        if (schema != null && result == null) {
            throw new AgentException(ErrorCodes.NO_RESULT,
                    "agent finished but produced no value matching outputSchema", false);
        }

        return new RunOutcome(result, text, reply.getUsage(), taskDir, trace.records());
    }

    /**
     * two_phase 第二阶段的墙钟预算：不给新预算，只吃任务剩下的时间，并兜底
     * {@link #MIN_PHASE_TIMEOUT_SECONDS} 秒。历史实现固定 120 秒，实测 300 字段 schema 的转换
     * 要 168 秒——固定的短超时会把本来能成的任务判成结构化失败。
     */
    private static Duration phaseTimeout(Duration budget, long startedAtNanos) {
        long spentMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
        long leftMs = budget == null ? Long.MAX_VALUE : budget.toMillis() - spentMs;
        return Duration.ofMillis(Math.max(leftMs, MIN_PHASE_TIMEOUT_SECONDS * 1000L));
    }

    /** One agent turn: schema rides along only when the first pass may stay free-form. */
    private static Mono<Msg> call(ReActAgent agent, String prompt, JsonNode schema,
                                  boolean freeFormFirst, RuntimeContext ctx) {
        List<Msg> messages = List.of(new UserMessage(prompt));
        return (schema == null || freeFormFirst)
                ? agent.call(messages, ctx)
                : agent.call(messages, schema, ctx);
    }

    /**
     * A document task that used no tool can only have been answered from model memory. Retry once
     * with the tool requirement stated explicitly; if the model still will not read the file, fail
     * the task rather than return invented field values.
     *
     * @return the retry reply, or {@code null} when the guard is switched off
     */
    private Msg answerGroundedInTools(String taskId, String instruction, Path taskDir, List<String> skills,
                                      String modelName, String prompt, JsonNode schema,
                                      boolean freeFormFirst, RuntimeContext ctx,
                                      ToolTrace trace, Duration budget, long startedAt) {
        if (!props.getRunner().isForceDocumentTool()) {
            log.warn("task {} answered a document instruction with no tool call (guard disabled)", taskId);
            return null;
        }
        long spentMs = (System.nanoTime() - startedAt) / 1_000_000;
        Duration left = budget.minusMillis(spentMs);
        if (left.toSeconds() < 20) {
            throw new AgentException(ErrorCodes.NO_RESULT,
                    "document instruction answered without any tool call, and no budget left to retry", false);
        }
        log.warn("task {} used no tool for a document instruction; retrying once with a forced tool call", taskId);
        // 链不清空：重试那一趟的调用接在后面，一条链完整反映这次任务到底跑过什么。
        ReActAgent retry = newAgent(taskDir, skills, modelName, trace, left);
        String nudged = prompt + "\n\n强制要求：本任务涉及的文件必须先通过工具读取。指令命中 身份证 / 印章 / 发票 / "
                + "营业执照的，第一个动作必须是 load_skill_through_path 加载对应技能（idcard-recognition / "
                + "seal-recognition / invoice-recognition / bizlic-recognition），再执行技能正文规定的命令；"
                + "只有不涉及这四类证件时，才用 extract_document_fields / understand_document。"
                + "没有工具返回的内容时不得给出任何字段值，缺失字段一律写 null。";
        Msg second = await(taskId, call(retry, nudged, schema, freeFormFirst, ctx), left);
        if (second == null) {
            throw new AgentException(ErrorCodes.NO_RESULT, "agent returned no message", true);
        }
        if (ungroundedAnswer(instruction, trace)) {
            throw new AgentException(ErrorCodes.NO_RESULT,
                    "instruction references a document but the model never called a tool; refusing to "
                            + "return values that could only come from model memory", false);
        }
        return second;
    }

    private static boolean ungroundedAnswer(String instruction, ToolTrace trace) {
        return trace.isEmpty() && DOCUMENT_REFERENCE.matcher(instruction).find();
    }

    private ReActAgent newAgent(Path taskDir, List<String> skills, String modelName,
                                ToolTrace trace, Duration toolTimeout) {
        AgentProperties.Runner runner = props.getRunner();
        PermissionMode mode;
        try {
            mode = PermissionMode.fromString(runner.getPermissionMode());
        } catch (Exception e) {
            log.warn("unknown permission mode '{}', falling back to BYPASS", runner.getPermissionMode());
            mode = PermissionMode.BYPASS;
        }
        String sysPrompt = runner.getSysPrompt();
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(runner.getName())
                .sysPrompt(sysPrompt)
                .model(modelFactory.build(modelName))
                .toolkit(toolkitFactory.build(taskDir))
                .permissionContext(PermissionContextState.builder().mode(mode).build())
                .maxIters(runner.getMaxIters())
                .middleware(new ToolTraceMiddleware(trace));

        // AgentScope applies ExecutionConfig.TOOL_DEFAULTS (5 min per tool call, no retry) to
        // every tool call unless the agent overrides it. Our task budgets are longer than that,
        // so the framework used to cut a long OCR off mid-run and hand the model a truncated
        // result to answer from. The timeout is therefore raised to the task's own wall clock:
        // the per-stage timeouts inside the document tools stay the real governors, and the
        // wall-clock await above still reports AGENT_TIMEOUT for the task as a whole.
        builder.toolExecutionConfig(
                ExecutionConfig.builder().timeout(toolTimeout).maxAttempts(1).build());

        // Skills flow through repositories, not SkillBox: skillBox(...) is deprecated in 2.0, and
        // the DynamicSkillMiddleware that skillRepository(...) installs rewrites the
        // <available_skills> block on every call — which is what makes an edited or newly added
        // SKILL.md apply without a restart. It also registers load_skill_through_path on the
        // toolkit, so only names and descriptions cost tokens until the model opens one.
        if (skillRepositoryFactory.enabled()) {
            builder.skillRepositories(skillRepositoryFactory.repositories())
                    .skillFilter(skillRepositoryFactory.filterFor(skills))
                    .skillCodeExecutionEnabled(props.getSkills().isCodeExecutionEnabled())
                    .skillWorkDir(skillRepositoryFactory.workDirFor(taskDir));
            log.debug("skills mounted requested={} available={}",
                    skills == null || skills.isEmpty() ? "(all)" : skills,
                    skillRepositoryFactory.availableNames());
        }
        return builder.build();
    }

    private Msg await(String taskId, Mono<Msg> call, Duration budget) {
        try {
            // .timeout() cancels upstream, so the agent stops rather than running on detached.
            return call.timeout(budget).block();
        } catch (Exception e) {
            Throwable root = Exceptions.unwrap(e);
            if (root instanceof TimeoutException || root == e && e instanceof TimeoutException) {
                throw new AgentException(ErrorCodes.AGENT_TIMEOUT,
                        "agent exceeded budget of " + budget.toSeconds() + "s", true, root);
            }
            if (root instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new AgentException(ErrorCodes.AGENT_INTERRUPTED, "run interrupted", true, root);
            }
            throw new AgentException(ErrorCodes.AGENT_EXECUTION_FAILED, describe(root), false, root);
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        String name = t.getClass().getSimpleName();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }

    private static String safeText(Msg msg) {
        try {
            return msg.getTextContent();
        } catch (Exception e) {
            log.debug("getTextContent failed", e);
            return null;
        }
    }

    /**
     * Pull the structured value out of the reply. Prefers AgentScope's metadata slot, then the
     * accessor, then falls back to parsing the assistant text as JSON so a model that ignored
     * the schema but printed valid JSON still yields a usable result.
     *
     * @return null when no structured value is available
     */
    private JsonNode extractStructured(Msg reply, JsonNode schema) {
        try {
            Map<String, Object> metadata = reply.getMetadata();
            if (metadata != null) {
                Object value = metadata.get(STRUCTURED_OUTPUT_KEY);
                if (value != null) {
                    return mapper.valueToTree(value);
                }
            }
            if (reply.hasStructuredData()) {
                Map<String, Object> asMap = reply.getStructuredData(true);
                if (asMap != null && !asMap.isEmpty()) {
                    return mapper.valueToTree(asMap);
                }
            }
        } catch (Exception e) {
            log.debug("structured metadata extraction failed for schema present={}", schema != null, e);
        }

        if (schema == null) {
            return null;
        }
        // Last resort: the model may have answered with a bare JSON document in text.
        String text = safeText(reply);
        if (text == null) {
            return null;
        }
        String candidate = stripCodeFence(text).trim();
        if (!candidate.startsWith("{") && !candidate.startsWith("[")) {
            return null;
        }
        try {
            return mapper.readTree(candidate);
        } catch (Exception e) {
            log.debug("text fallback was not valid JSON");
            return null;
        }
    }

    /** Remove a leading/trailing markdown code fence if the model wrapped its answer. */
    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        String body = trimmed.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return closing >= 0 ? body.substring(0, closing) : body;
    }
}

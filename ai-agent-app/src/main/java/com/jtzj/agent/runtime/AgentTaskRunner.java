package com.jtzj.agent.runtime;

import com.jtzj.agent.core.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private final AgentProperties props;
    private final ModelFactory modelFactory;
    private final ToolkitFactory toolkitFactory;
    private final TaskWorkspaceFactory workspaceFactory;
    private final SkillRepositoryFactory skillRepositoryFactory;
    private final StructuredOutputConverter structuredConverter;
    private final ObjectMapper mapper;

    public AgentTaskRunner(AgentProperties props, ModelFactory modelFactory,
                           ToolkitFactory toolkitFactory, TaskWorkspaceFactory workspaceFactory,
                           SkillRepositoryFactory skillRepositoryFactory,
                           StructuredOutputConverter structuredConverter,
                           ObjectMapper mapper) {
        this.props = props;
        this.modelFactory = modelFactory;
        this.toolkitFactory = toolkitFactory;
        this.workspaceFactory = workspaceFactory;
        this.skillRepositoryFactory = skillRepositoryFactory;
        this.structuredConverter = structuredConverter;
        this.mapper = mapper;
    }

    /**
     * @param result      structured JSON when the task carried an outputSchema, else null
     * @param text        final assistant text (always best-effort)
     * @param usage       token usage, may be null when the provider reports none
     * @param workspace   the task's private directory, where any produced files live
     */
    public record RunOutcome(JsonNode result, String text, ChatUsage usage, Path workspace) {
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
     * @throws AgentException on timeout, interruption, or agent failure
     */
    public RunOutcome run(String taskId, String instruction, JsonNode schema,
                          List<String> skills, Duration budget, String modelName) {
        Path taskDir = workspaceFactory.create(taskId);
        try {
            return runInWorkspace(taskId, instruction, schema, skills, budget, modelName, taskDir);
        } finally {
            // OCR 中间文本只在本次运行内有意义（同一来源换字段不再重识别）；跑完即删，原文不留盘。
            if (props.getTools().isDeleteWorkspaceOnFinish()) {
                // 不保留审计：整个任务目录一起清掉，否则每份扫描件都在盘上留几十 MB，只涨不缩。
                workspaceFactory.delete(taskId);
            } else {
                workspaceFactory.deleteScratch(taskDir);
            }
        }
    }

    private RunOutcome runInWorkspace(String taskId, String instruction, JsonNode schema,
                                      List<String> skills, Duration budget, String modelName,
                                      Path taskDir) {
        Set<String> invokedTools = ConcurrentHashMap.newKeySet();
        ReActAgent agent = newAgent(taskDir, skills, modelName, invokedTools);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("task-" + taskId)
                .userId("agent-service")
                .build();

        // Skills the caller named explicitly are injected here instead of being left to the
        // model's discretion; see SkillRepositoryFactory.inlineFor.
        String skillBlock = skillRepositoryFactory.inlineFor(skills);
        String prompt = skillBlock.isEmpty()
                ? instruction
                : "已加载的技能规范（必须严格遵循，不要再调用 load_skill_through_path 加载它们）：\n\n"
                        + skillBlock + "\n任务指令：\n" + instruction;

        AgentProperties.StructuredOutputMode mode = props.resolveModel(modelName).getStructuredOutputMode();
        // TWO_PHASE / OFF：第一趟不带 schema 跑，让 understand_image 等工具能正常调用
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
        if (ungroundedAnswer(instruction, invokedTools)) {
            reply = answerGroundedInTools(taskId, instruction, taskDir, skills, modelName, prompt, schema,
                    freeFormFirst, ctx, invokedTools, budget, startedAt);
            if (reply == null) {
                throw new AgentException(ErrorCodes.NO_RESULT,
                        "document instruction answered without any tool call (guard disabled)", false);
            }
        }

        String text = safeText(reply);
        JsonNode result;
        if (mode == AgentProperties.StructuredOutputMode.TWO_PHASE && schema != null) {
            result = structuredConverter.toStructured(text, schema, modelName);
        } else {
            result = extractStructured(reply, schema);
        }
        if (schema != null && result == null) {
            throw new AgentException(ErrorCodes.NO_RESULT,
                    "agent finished but produced no value matching outputSchema", false);
        }

        return new RunOutcome(result, text, reply.getUsage(), taskDir);
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
                                      Set<String> invokedTools, Duration budget, long startedAt) {
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
        invokedTools.clear();
        ReActAgent retry = newAgent(taskDir, skills, modelName, invokedTools);
        String nudged = prompt + "\n\n强制要求：本任务涉及的文件必须先通过工具读取（extract_document_fields / "
                + "understand_document，或匹配技能规定的接口调用）。没有工具返回的内容时不得给出任何字段值，"
                + "缺失字段一律写 null。";
        Msg second = await(taskId, call(retry, nudged, schema, freeFormFirst, ctx), left);
        if (second == null) {
            throw new AgentException(ErrorCodes.NO_RESULT, "agent returned no message", true);
        }
        if (ungroundedAnswer(instruction, invokedTools)) {
            throw new AgentException(ErrorCodes.NO_RESULT,
                    "instruction references a document but the model never called a tool; refusing to "
                            + "return values that could only come from model memory", false);
        }
        return second;
    }

    private static boolean ungroundedAnswer(String instruction, Set<String> invokedTools) {
        return invokedTools.isEmpty() && DOCUMENT_REFERENCE.matcher(instruction).find();
    }

    private ReActAgent newAgent(Path taskDir, List<String> skills, String modelName,
                                Set<String> invokedTools) {
        AgentProperties.Runner runner = props.getRunner();
        PermissionMode mode;
        try {
            mode = PermissionMode.fromString(runner.getPermissionMode());
        } catch (Exception e) {
            log.warn("unknown permission mode '{}', falling back to BYPASS", runner.getPermissionMode());
            mode = PermissionMode.BYPASS;
        }
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(runner.getName())
                .sysPrompt(runner.getSysPrompt())
                .model(modelFactory.build(modelName))
                .toolkit(toolkitFactory.build(taskDir))
                .permissionContext(PermissionContextState.builder().mode(mode).build())
                .maxIters(runner.getMaxIters())
                .hook(new ToolUsageHook(invokedTools));

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

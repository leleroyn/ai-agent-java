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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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

    /** 指令里出现图片来源：证件通道（ips idcard / bizlic）必须跑，不能拿「PDF 不支持」当挡箭牌。 */
    private static final Pattern IMAGE_SOURCE = Pattern.compile(
            "(?i)\\S*\\.(?:jpe?g|png|webp|bmp|gif)(?![0-9A-Za-z])");

    /** shell 链里跑过哪些 ips 子命令，用来数「命中几类、跑了几类」。 */
    private static final Pattern IPS_SUBCOMMAND = Pattern.compile(
            "\\bips\\s+(idcard|bizlic|invoice|seal)\\b");

    /** two_phase 第二阶段的地板超时：任务预算已耗尽时也给足这么多秒做转换（见 {@link #phaseTimeout}）。 */
    private static final long MIN_PHASE_TIMEOUT_SECONDS = 60;

    private final AgentProperties props;
    private final ModelFactory modelFactory;
    private final ToolkitFactory toolkitFactory;
    private final TaskWorkspaceFactory workspaceFactory;
    private final SourceIngestor sourceIngestor;
    private final SkillRepositoryFactory skillRepositoryFactory;
    private final SkillRouter skillRouter;
    private final StructuredOutputConverter structuredConverter;
    private final ObjectMapper mapper;

    public AgentTaskRunner(AgentProperties props, ModelFactory modelFactory,
                           ToolkitFactory toolkitFactory, TaskWorkspaceFactory workspaceFactory,
                           SourceIngestor sourceIngestor,
                           SkillRepositoryFactory skillRepositoryFactory,
                           SkillRouter skillRouter,
                           StructuredOutputConverter structuredConverter,
                           ObjectMapper mapper) {
        this.props = props;
        this.modelFactory = modelFactory;
        this.toolkitFactory = toolkitFactory;
        this.workspaceFactory = workspaceFactory;
        this.sourceIngestor = sourceIngestor;
        this.skillRepositoryFactory = skillRepositoryFactory;
        this.skillRouter = skillRouter;
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
     * @param skills      调用方点名的技能；服务端再并上关键字命中的（见 SkillRouter），空 = 不点名
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
        // 服务端路由：命中哪几类证件在这里算，和调用方点名的取并集。技能正文直接注入本条任务消息，
        // 模型不必自己去 load；命中多类就注入几份，一次到位（省往返的收益见 SkillRouter）。
        List<String> routed = skillRouter.detect(rewritten);
        List<String> effective = skillRouter.merge(skills, routed);
        if (!routed.isEmpty()) {
            log.debug("task {} skill route: hits={} requested={} injected={}", taskId, routed, skills, effective);
        }
        ReActAgent agent = newAgent(taskDir, effective, modelName, trace, budget);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("task-" + taskId)
                .userId("agent-service")
                .build();

        // Skills the caller named explicitly are injected here instead of being left to the
        // model's discretion; see SkillRepositoryFactory.inlineFor.
        String skillBlock = skillRepositoryFactory.inlineFor(effective);
        String prompt = skillBlock.isEmpty()
                ? rewritten
                : "已加载的技能规范（命中几类就是几份，每一份都要照正文执行；正文已在本消息里，"
                        + "不要再调 load_skill_through_path 加载同一个技能）：\n\n"
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
        if (needsToolNudge(instruction, trace, routed)) {
            reply = answerGroundedInTools(taskId, instruction, taskDir, effective, routed, modelName,
                    prompt, schema, freeFormFirst, ctx, trace, budget, startedAt);
            if (reply == null) {
                throw new AgentException(ErrorCodes.NO_RESULT,
                        "document instruction answered without any tool call (guard disabled)", false);
            }
        }

        // 不带 outputSchema 时模型常把 JSON 包进 ```json 围栏（实测一批 6 条里 5 条如此），而 sys-prompt
        // 明令不许用围栏。result_json 侧早有 stripCodeFence 兜底，result_text 也给同一个兜底：
        // 整段就是一个围栏、里面正好是 JSON 才剥，围栏外写着说明文字的答复原样保留。
        String text = stripJsonFence(safeText(reply));
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
     * <p>第二个触发条件是「命中 N 类证件、链里却跑不满 N 条 ips 命令」。补完一轮仍缺类型时只记 WARN
     * 不判失败：技能报「未检出」是权威终局，把它硬判成任务错误等于宣布「这张图里没有营业执照」是故障。
     *
     * @return the retry reply, or {@code null} when the guard is switched off
     */
    private Msg answerGroundedInTools(String taskId, String instruction, Path taskDir, List<String> skills,
                                      List<String> routed, String modelName, String prompt, JsonNode schema,
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
        log.warn("task {} needs a forced tool call (tools so far: {}); retrying once", taskId, trace.tools());
        // 链不清空：重试那一趟的调用接在后面，一条链完整反映这次任务到底跑过什么。
        ReActAgent retry = newAgent(taskDir, skills, modelName, trace, left);
        String commands = SkillRouter.commandsFor(routed);
        String nudged = commands.isEmpty()
                ? prompt + "\n\n强制要求：本任务涉及的文件必须先通过工具读取，禁止凭记忆给出字段值。"
                        + "没有工具返回的内容时缺失字段一律写 null。"
                : prompt + "\n\n强制要求：本任务命中 " + routed.size() + " 类证件，技能正文已在本消息开头给出，"
                        + "不要再调 load_skill_through_path。每一类都必须各自跑出结论才允许收尾："
                        + commands
                        + "一条命令一个文件，多个文件逐条执行。全部命中类型的命令跑出结果之前，"
                        + "禁止 extract_document_fields 与 understand_document；最后把各类字段合并进同一个 JSON 对象。"
                        + "技能报「未检出」就是该类的终局结论，不得换通用工具再查；"
                        + "没有工具返回的内容时不得给出任何字段值，缺失字段一律写 null。";
        Msg second = await(taskId, call(retry, nudged, schema, freeFormFirst, ctx), left);
        if (second == null) {
            throw new AgentException(ErrorCodes.NO_RESULT, "agent returned no message", true);
        }
        if (certChannelIncomplete(instruction, trace, routed)) {
            log.warn("task {} still missing certificate channels after nudge: expected {}",
                    taskId, SkillRouter.commandsFor(routed));
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

    /**
     * 要不要强制重试：整趟一个工具都没调（老闸，答案只可能来自模型记忆），或者<b>命中了证件类型却没把
     * 每一类都跑完</b>（新闸）。注入技能正文解决的是「知不知道要做」，这一条解决的是「做没做完」：
     * 多证件任务里模型只跑一类就收尾，字段静默缺一半，生产上比慢几秒严重得多。
     */
    private static boolean needsToolNudge(String instruction, ToolTrace trace, List<String> routed) {
        return ungroundedAnswer(instruction, trace) || certChannelIncomplete(instruction, trace, routed);
    }

    /**
     * 命中的每一类是否都跑过自己的 {@code ips} 子命令。
     *
     * <p>两种情况不判（否则会误伤、白烧一轮）：调用链采集关闭时读不到命令参数；指令里只有 PDF 来源时，
     * 只收图片的 idcard / bizlic 按技能正文改走通用抽取正是正确行为。
     */
    private static boolean certChannelIncomplete(String instruction, ToolTrace trace, List<String> routed) {
        if (routed.isEmpty()) {
            return false;
        }
        Set<String> ran = ipsSubcommands(trace);
        if (ran == null) {
            return false;
        }
        boolean image = IMAGE_SOURCE.matcher(instruction).find();
        List<String> expected = new ArrayList<>(routed.size());
        for (String skill : routed) {
            String command = SkillRouter.subcommandOf(skill);
            if (command == null || (!image && SkillRouter.imageOnly(skill))) {
                continue;
            }
            expected.add(command);
        }
        if (expected.isEmpty() || ran.containsAll(expected)) {
            return false;
        }
        // 一条 ips 都没跑、又没有图片来源：整条任务只有 PDF，通用抽取正是技能正文指示的路径。
        return image || !ran.isEmpty();
    }

    /**
     * 链里出现过的 {@code ips} 子命令。
     *
     * @return 采集关闭、读不到参数时返回 null，调用方据此放弃判定
     */
    private static Set<String> ipsSubcommands(ToolTrace trace) {
        List<ToolCallRecord> records = trace.records();
        if (records.isEmpty()) {
            return trace.isEmpty() ? Set.of() : null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (ToolCallRecord record : records) {
            if (record.args() == null) {
                return null;
            }
            Matcher matcher = IPS_SUBCOMMAND.matcher(record.args());
            while (matcher.find()) {
                out.add(matcher.group(1));
            }
        }
        return out;
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

    /**
     * 整段回复就是一个代码围栏、且围栏里是 JSON 时返回围栏内容，否则原样返回。
     *
     * <p>只认「整段就是围栏」：模型在围栏外面写解释文字时，那段文字是给人看的答复，剥掉围栏会改变
     * result_text 的形状；纯围栏包装才是违反 sys-prompt 的那一类。
     */
    private String stripJsonFence(String text) {
        if (text == null) {
            return null;
        }
        String stripped = stripCodeFence(text).trim();
        if (stripped.equals(text.trim()) || (!stripped.startsWith("{") && !stripped.startsWith("["))) {
            return text;
        }
        try {
            mapper.readTree(stripped);
        } catch (Exception e) {
            return text;
        }
        return stripped;
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

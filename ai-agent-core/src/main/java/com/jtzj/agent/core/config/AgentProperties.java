package com.jtzj.agent.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binding for the {@code agent.*} configuration tree. */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /**
     * 命名主模型 profile（如 flash / pro），彼此完全独立、无共享属性。
     * task 可通过请求里的 {@code model} 字段选一个；缺省用 {@link #defaultModel}。
     */
    private final Map<String, Model> models = new LinkedHashMap<>();
    /** 未指定 model 时使用的 profile 名。 */
    private String defaultModel = "flash";
    private final Vision vision = new Vision();
    private final Pdfs pdfs = new Pdfs();
    private final Ips ips = new Ips();
    private final Runner runner = new Runner();
    private final Execution execution = new Execution();
    private final Tools tools = new Tools();
    private final Skills skills = new Skills();
    private final Limits limits = new Limits();

    /**
     * 纯文本子调用（{@code extract_document_fields} / {@code understand_document} 的文本分支）
     * 用的模型 profile 名，对应 {@code agent.models.*}。OCR 之后手上只剩文本，用文本模型比
     * 复用视觉端点更准（实测同一份营业执照 OCR 文本：视觉端点 6 字段稳定漏 1 个，pro 全对）。
     * 留空或 profile 不存在 = 回退视觉端点（旧行为）。
     */
    private String textModel = "pro";

    public Map<String, Model> getModels() {
        return models;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    /**
     * 解析要用的主模型 profile。{@code requested} 为空 → 用 {@code defaultModel}。
     * 未知 profile 名 → 抛 {@link IllegalArgumentException}（由调用方转成错误）。
     */
    public Model resolveModel(String requested) {
        String name = (requested == null || requested.isBlank()) ? defaultModel : requested.trim();
        Model m = models.get(name);
        if (m == null) {
            throw new IllegalArgumentException(
                    "unknown model profile '" + name + "'; available: " + models.keySet());
        }
        return m;
    }

    public String getTextModel() {
        return textModel;
    }

    public void setTextModel(String textModel) {
        this.textModel = textModel;
    }

    /** 纯文本子调用该用哪个模型 profile；未配置或名字未知返回 null（=继续用视觉端点）。 */
    public Model resolveTextModel() {
        if (textModel == null || textModel.isBlank()) {
            return null;
        }
        return models.get(textModel.trim());
    }

    public Vision getVision() {
        return vision;
    }

    public Pdfs getPdfs() {
        return pdfs;
    }
    public Ips getIps() {
        return ips;
    }

    public Runner getRunner() {
        return runner;
    }

    public Execution getExecution() {
        return execution;
    }

    public Tools getTools() {
        return tools;
    }

    public Skills getSkills() {
        return skills;
    }

    public Limits getLimits() {
        return limits;
    }

    /**
     * 结构化输出（outputSchema）产出策略。不同端点/模型对 tools+response_format 的
     * 支持度不同，用这一个开关适配，换模型只改配置不改代码。
     * <ul>
     *   <li>{@code AUTO}：保持旧行为，由 nativeStructuredOutput(WithTools) 两个布尔决定（向后兼容）。</li>
     *   <li>{@code NATIVE}：单趟，带 tools 时直接发 response_format:json_schema（仅适合支持
     *       interleaving 的端点，如 vLLM/OpenAI；llama.cpp 会因此抑制工具调用）。</li>
     *   <li>{@code TOOL}：单趟，走 AgentScope 合成 generate_response 工具。</li>
     *   <li>{@code TWO_PHASE}：先不带 schema 跑工具拿自由文本，再用不带 tools+response_format
     *       的第二次调用把文本转 JSON。最稳，llama.cpp+minicpm 下图片+schema 也能对。</li>
     *   <li>{@code OFF}：不强制，仅兜底解析回复文本里的 JSON。</li>
     * </ul>
     */
    public enum StructuredOutputMode {
        AUTO, NATIVE, TOOL, TWO_PHASE, OFF
    }

    /** LLM endpoint. Defaults match the locally verified llama.cpp OpenAI-compatible server. */
    public static class Model {
        private String baseUrl = "http://192.168.1.250:8009/v1";
        private String apiKey = "local";
        private String name = "qwen";

        /**
         * Must stay false against this endpoint: sending {@code tools} together with
         * {@code response_format: json_schema} yields HTTP 400 "failed to parse grammar".
         */
        private boolean nativeStructuredOutputWithTools = false;
        private boolean nativeStructuredOutput = true;

        /** 结构化输出策略，默认 AUTO（=旧行为）。 */
        private StructuredOutputMode structuredOutputMode = StructuredOutputMode.AUTO;

        /**
         * two_phase 第二阶段（自由文本→JSON）的输出预算（max_tokens）。
         * 留空=沿用本 profile 的 max-tokens。代码侧不再有写死的 2048 上限，flash 与 pro
         * 走同一套算法；真正的天花板是端点 n_ctx（schema + 结果文本 + 本值 必须小于它）。
         */
        private Integer structuredMaxTokens;

        /** "none" disables reasoning; see application.yml for the measured rationale. Blank = unset. */
        private String reasoningEffort = "none";

        private Integer maxTokens = 4096;
        private Double temperature = 0.2;
        private Integer contextWindowSize = 262144;
        /** strata_checkpoint 参数（llama.cpp/strata 特有）；空=不下发，使用 strata 时配为 false。 */
        private String strataCheckpoint;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isNativeStructuredOutputWithTools() {
            return nativeStructuredOutputWithTools;
        }

        public void setNativeStructuredOutputWithTools(boolean nativeStructuredOutputWithTools) {
            this.nativeStructuredOutputWithTools = nativeStructuredOutputWithTools;
        }

        public StructuredOutputMode getStructuredOutputMode() {
            return structuredOutputMode;
        }

        public void setStructuredOutputMode(StructuredOutputMode structuredOutputMode) {
            this.structuredOutputMode = structuredOutputMode;
        }

        public Integer getStructuredMaxTokens() {
            return structuredMaxTokens;
        }

        public void setStructuredMaxTokens(Integer structuredMaxTokens) {
            this.structuredMaxTokens = structuredMaxTokens;
        }

        public boolean isNativeStructuredOutput() {
            return nativeStructuredOutput;
        }

        public void setNativeStructuredOutput(boolean nativeStructuredOutput) {
            this.nativeStructuredOutput = nativeStructuredOutput;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getContextWindowSize() {
            return contextWindowSize;
        }

        public void setContextWindowSize(Integer contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
        }

        public String getStrataCheckpoint() {
            return strataCheckpoint;
        }

        public void setStrataCheckpoint(String strataCheckpoint) {
            this.strataCheckpoint = strataCheckpoint;
        }
    }

    /**
     * 独立的视觉（多模态）模型端点，只给 {@code understand_document} 的看图通道用。
     *
     * <p><b>为什么不复用 {@link #model}</b>：主模型只处理文本，图片字节绝不进它的上下文——
     * 否则 ReAct 每一轮都带着图，token 膨胀、成本高，而且把图泄露给了主模型。图片只流向
     * 这里单独配置的 VL 端点，每次是无状态的单次调用。
     *
     * <p>该端点必须是带 mmproj（多模态投影器）的真 VL 模型。实测：不带 mmproj 的 llama.cpp
     * 端点会直接返回 {@code 500 image input is not supported ... provide the mmproj}。
     */
    public static class Vision {
        /** 必须是带 mmproj 的 OpenAI 兼容 VL 端点。 */
        private String baseUrl = "http://192.168.1.250:8008/v1";
        private String apiKey = "local";
        private String name = "qwenvl";
        /** 单次工具调用最多理解几张图（多图在一次请求里跨图推理）。 */
        private int maxImages = 8;
        /** 单张图下载字节上限，防止被超大文件或非图片地址拖垮。 */
        private int maxImageBytes = 8 * 1024 * 1024;
        /** 单次视觉调用（下载 + 推理）的整体超时秒数。 */
        private int timeoutSeconds = 120;
        /** 视觉模型采样温度；理解类任务默认 0 求稳定。 */
        private Double temperature = 0.0;
        /** 视觉模型推理强度：{@code "none"} 关闭思考（llama.cpp 经 chat_template_kwargs.enable_thinking 生效）；空=不下发该参数。 */
        private String reasoningEffort = "none";
        /** 视觉模型最大输出 token 数；null=不下发（用后端默认）。 */
        private Integer maxTokens;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getMaxImages() {
            return maxImages;
        }

        public void setMaxImages(int maxImages) {
            this.maxImages = maxImages;
        }

        public int getMaxImageBytes() {
            return maxImageBytes;
        }

        public void setMaxImageBytes(int maxImageBytes) {
            this.maxImageBytes = maxImageBytes;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    /** Per-task ReActAgent settings. */
    public static class Runner {
        private String name = "task-agent";
        // 关于当前时间的两句约束是必要的：模型不知道现在几点，不约束时它会拿 shell 的 date
        // 去算日期——Windows 上 `date` 是交互式命令，会挂到任务超时（实测浪费满 120 秒）。
        private String sysPrompt = "You are an automated worker agent. Complete the instruction and produce the requested result. "
                + "Answer directly from your own knowledge and reasoning whenever that is enough; call a tool only when it is genuinely needed. "
                + "When the instruction depends on the current date or time, call get_system_time instead of guessing. "
                + "For date arithmetic (for example a date N days from now), use get_system_time with offset_days; never run shell date commands.";
        private int maxIters = 20;
        /**
         * Fail a document task whose run never called a tool, instead of returning whatever the
         * model recalled. Off switches the guard back to log-only.
         */
        private boolean forceDocumentTool = true;
        /** PermissionMode name: DEFAULT | ACCEPT_EDITS | EXPLORE | BYPASS | DONT_ASK */
        private String permissionMode = "BYPASS";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSysPrompt() {
            return sysPrompt;
        }

        public void setSysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
        }

        public boolean isForceDocumentTool() {
            return forceDocumentTool;
        }

        public void setForceDocumentTool(boolean forceDocumentTool) {
            this.forceDocumentTool = forceDocumentTool;
        }

        public int getMaxIters() {
            return maxIters;
        }

        public void setMaxIters(int maxIters) {
            this.maxIters = maxIters;
        }

        public String getPermissionMode() {
            return permissionMode;
        }

        public void setPermissionMode(String permissionMode) {
            this.permissionMode = permissionMode;
        }
    }

    /** Concurrency, queueing and timeout budget. */
    public static class Execution {
        /**
         * Async-lane worker threads; each runs one task at a time, so this is the exact async
         * concurrency. These threads never claim sync rows (see {@code syncConcurrency}).
         * Renamed from {@code maxConcurrent}: once sync got its own lane, the old name stopped
         * saying which lane it bounded.
         */
        private int asyncConcurrency = 1;
        /**
         * Sync-lane worker threads, resident like the async workers. An idle lane costs one
         * indexed claim query per worker per {@code maxPollBackoffSeconds}. {@code 0} switches the
         * lane off and sync rows fall back to the async workers, claimed ahead of async work.
         */
        private int syncConcurrency = 1;
        private int defaultTimeoutSeconds = 120;
        private int maxTimeoutSeconds = 900;

        /** How long a claim stays valid; must be renewed while the task runs. */
        private int leaseSeconds = 90;
        /** How often to requeue/fail work whose lease lapsed (dead worker recovery). */
        private int leaseReapSeconds = 30;
        /** How many times a task may be claimed in total before it is failed. */
        private int maxAttempts = 2;
        /** Retry delay after a claim query fails; idle workers park on a signal, they do not poll. */
        private int pollIntervalMs = 250;
        private int maxPollBackoffSeconds = 3;
        /** Candidate ids inspected per poll before giving up (reduces claim contention). */
        private int claimPeek = 5;
        /**
         * Reject new submissions once this many tasks are still {@code accepted}. Zero disables
         * the limit. Replaces the old in-memory queue bound, which no longer exists.
         */
        private int maxQueuedTasks = 1000;

        public int getAsyncConcurrency() {
            return asyncConcurrency;
        }

        public void setAsyncConcurrency(int asyncConcurrency) {
            this.asyncConcurrency = asyncConcurrency;
        }

        public int getSyncConcurrency() {
            return syncConcurrency;
        }

        public void setSyncConcurrency(int syncConcurrency) {
            this.syncConcurrency = syncConcurrency;
        }

        public int getDefaultTimeoutSeconds() {
            return defaultTimeoutSeconds;
        }

        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
            this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        }

        public int getMaxTimeoutSeconds() {
            return maxTimeoutSeconds;
        }

        public void setMaxTimeoutSeconds(int maxTimeoutSeconds) {
            this.maxTimeoutSeconds = maxTimeoutSeconds;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getLeaseReapSeconds() {
            return leaseReapSeconds;
        }

        public void setLeaseReapSeconds(int leaseReapSeconds) {
            this.leaseReapSeconds = leaseReapSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(int pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getMaxPollBackoffSeconds() {
            return maxPollBackoffSeconds;
        }

        public void setMaxPollBackoffSeconds(int maxPollBackoffSeconds) {
            this.maxPollBackoffSeconds = maxPollBackoffSeconds;
        }

        public int getClaimPeek() {
            return claimPeek;
        }

        public void setClaimPeek(int claimPeek) {
            this.claimPeek = claimPeek;
        }

        public int getMaxQueuedTasks() {
            return maxQueuedTasks;
        }

        public void setMaxQueuedTasks(int maxQueuedTasks) {
            this.maxQueuedTasks = maxQueuedTasks;
        }
    }

    /** Built-in tool exposure. All four default on, as decided; BYPASS permission mode is required. */
    public static class Tools {
        private boolean shell = true;
        private boolean readFile = true;
        private boolean writeFile = true;
        private boolean todo = true;
        /**
         * 自定义工具：系统时间（{@code get_system_time}）。默认开启——没有它，模型对
         * 「今天」「是否过期」这类问题只能猜。
         */
        private boolean systemTime = true;
        /**
         * 自定义工具：PDF 自由理解（{@code understand_document}）。默认开启。读某几页/小文档做自由问答。
         */
        private boolean documentUnderstand = true;
        /**
         * 看图开关：{@code understand_document} 的图片/PDF 视觉通道（图字节送到 {@code agent.vision}）
         * 是否可用。默认 {@code true}。置 {@code false} 时本工具不再看图：图片/PDF 先 OCR 成文本
         * （{@code .ocr/} 下的同名 txt，与抽取共用同一份缓存），再交给 {@code agent.text-model} 问答。
         * {@code extract_document_fields} 本来就不看图（走 OCR 文本通道），不受本开关影响。
         */
        private boolean documentUnderstandVision = true;
        /**
         * 自定义工具：PDF 字段抽取（{@code extract_document_fields}）。默认开启。服务端遍历整篇、
         * 分批并发结构化抽取、确定性归并并回页码，召回不依赖主模型分页。
         */
        private boolean documentExtract = true;

        private String workingDir = "./agent-workspace";
        /** Empty means no allowlist is configured. */
        private List<String> shellAllowedCommands = new ArrayList<>();
        /**
         * 命令级黑名单：按 {@code ; | & 换行} 拆段后取每段第一个 token，去引号取 basename，
         * 大小写不敏感比对。与白名单互补——白名单非空时框架会连带禁掉管道/组合命令，混合任务
         * 就没法用；黑名单只掐明确危险的命令（默认 {@code curl,wget}：它们会把指令里已就绪的
         * 文件再下一遍，导致同一份内容被重复 OCR）。空列表 = 不禁任何命令。
         */
        private List<String> shellDeniedCommands = new ArrayList<>();
        /**
         * Delete {@code <working-dir>/<taskId>} once a task reaches a terminal state.
         * Defaults to false so produced files remain inspectable; enable it when the
         * workspace is treated as scratch space.
         */
        private boolean deleteWorkspaceOnFinish = false;

        public boolean isShell() {
            return shell;
        }

        public void setShell(boolean shell) {
            this.shell = shell;
        }

        public boolean isReadFile() {
            return readFile;
        }

        public void setReadFile(boolean readFile) {
            this.readFile = readFile;
        }

        public boolean isWriteFile() {
            return writeFile;
        }

        public void setWriteFile(boolean writeFile) {
            this.writeFile = writeFile;
        }

        public boolean isTodo() {
            return todo;
        }

        public boolean isSystemTime() {
            return systemTime;
        }

        public void setSystemTime(boolean systemTime) {
            this.systemTime = systemTime;
        }

        public boolean isDocumentUnderstand() {
            return documentUnderstand;
        }

        public void setDocumentUnderstand(boolean documentUnderstand) {
            this.documentUnderstand = documentUnderstand;
        }

        public boolean isDocumentUnderstandVision() {
            return documentUnderstandVision;
        }

        public void setDocumentUnderstandVision(boolean documentUnderstandVision) {
            this.documentUnderstandVision = documentUnderstandVision;
        }

        public boolean isDocumentExtract() {
            return documentExtract;
        }

        public void setDocumentExtract(boolean documentExtract) {
            this.documentExtract = documentExtract;
        }



        public void setTodo(boolean todo) {
            this.todo = todo;
        }

        public String getWorkingDir() {
            return workingDir;
        }

        public void setWorkingDir(String workingDir) {
            this.workingDir = workingDir;
        }

        public List<String> getShellAllowedCommands() {
            return shellAllowedCommands;
        }

        public void setShellAllowedCommands(List<String> shellAllowedCommands) {
            this.shellAllowedCommands = shellAllowedCommands;
        }

        public List<String> getShellDeniedCommands() {
            return shellDeniedCommands;
        }

        public void setShellDeniedCommands(List<String> shellDeniedCommands) {
            this.shellDeniedCommands = shellDeniedCommands;
        }

        public boolean isDeleteWorkspaceOnFinish() {
            return deleteWorkspaceOnFinish;
        }

        public void setDeleteWorkspaceOnFinish(boolean deleteWorkspaceOnFinish) {
            this.deleteWorkspaceOnFinish = deleteWorkspaceOnFinish;
        }
    }

    /**
     * Reusable skills (AgentScope {@code io.agentscope.core.skill}).
     *
     * <p>Discovery is dynamic: {@code FileSystemSkillRepository} re-reads any SKILL.md whose
     * mtime or size changed, so adding a skill directory takes effect on the next task without
     * a restart.
     */
    public static class Skills {
        /**
         * Master switch. When false no skill repository is mounted, nothing is injected into
         * the prompt, and a request carrying {@code skills} is rejected with 400.
         */
        private boolean enabled = true;
        /** Classpath folder holding {@code <skill-name>/SKILL.md}; skipped when not in the jar. */
        private String classpathLocation = "skills";
        /**
         * Operator-managed skills on disk, higher priority than the classpath ones (a same-named
         * skill here overrides the built-in copy). Created on demand.
         */
        private String directory = "./agent-skills";
        /**
         * When true only SKILL.md is read up front and supporting files stay on disk, reached
         * through {@code load_skill_through_path}. Keep true unless the library is small.
         */
        private boolean lazy = true;
        /**
         * Allows a skill's own scripts to be run through the shell tool and adds the
         * {@code <files-root>} instructions to the prompt. Off by default: with shell enabled and
         * no authentication this would widen the attack surface from "whatever the instruction
         * says" to "whatever anyone who can write the skills directory puts in a script".
         */
        private boolean codeExecutionEnabled = false;
        /** Upper bound on how many skill names a single request may select. */
        private int maxRequested = 8;
        /**
         * Ceiling on the total injected skill text. Selected skills are inlined into the prompt,
         * and this endpoint runs with a modest max-tokens budget, so an oversized skill body
         * would crowd out the answer instead of improving it.
         */
        private int maxInlineChars = 12000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClasspathLocation() {
            return classpathLocation;
        }

        public void setClasspathLocation(String classpathLocation) {
            this.classpathLocation = classpathLocation;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public boolean isLazy() {
            return lazy;
        }

        public void setLazy(boolean lazy) {
            this.lazy = lazy;
        }

        public boolean isCodeExecutionEnabled() {
            return codeExecutionEnabled;
        }

        public void setCodeExecutionEnabled(boolean codeExecutionEnabled) {
            this.codeExecutionEnabled = codeExecutionEnabled;
        }

        public int getMaxRequested() {
            return maxRequested;
        }

        public void setMaxRequested(int maxRequested) {
            this.maxRequested = maxRequested;
        }

        public int getMaxInlineChars() {
            return maxInlineChars;
        }

        public void setMaxInlineChars(int maxInlineChars) {
            this.maxInlineChars = maxInlineChars;
        }
    }

    /** Input size guards. */
    public static class Limits {
        private int maxInstructionChars = 32768;
        private int maxSchemaChars = 8192;
        private int maxMetadataChars = 8192;

        public int getMaxInstructionChars() {
            return maxInstructionChars;
        }

        public void setMaxInstructionChars(int maxInstructionChars) {
            this.maxInstructionChars = maxInstructionChars;
        }

        public int getMaxSchemaChars() {
            return maxSchemaChars;
        }

        public void setMaxSchemaChars(int maxSchemaChars) {
            this.maxSchemaChars = maxSchemaChars;
        }

        public int getMaxMetadataChars() {
            return maxMetadataChars;
        }

        public void setMaxMetadataChars(int maxMetadataChars) {
            this.maxMetadataChars = maxMetadataChars;
        }
    }

    /**
     * PDF 工具配置，服务两个工具：{@code understand_document}（自由问答，一批页）与
     * {@code extract_document_fields}（服务端遍历整篇、分批并发结构化抽取、确定性归并）。
     *
     * <p>两者都基于 {@code pdftoppm} 逐页栅格化后交给 {@link Vision}；图片字节不进主模型上下文。
     * {@code extract_document_fields} 靠服务端遍历保证召回，语义总结交给主模型。
     */
    public static class Pdfs {
        private boolean enabled = true;
        /**
         * 每批交给视觉模型的页数（{@code understand_document} 的单次上限；{@code extract_document_fields}
         * 每批喂多少页）。实际上限 = {@code min(本值, agent.vision.max-images)}。
         */
        private int pagesPerCall = 6;
        /** {@code extract_document_fields} 遍历整篇时的并发批数（抽取阶段的分块并发）。 */
        private int concurrency = 4;
        /**
         * 扫描版 PDF 逐页 OCR 的并发 worker 数。写入仍严格按页序（一批内先全部识别完、再按页号落盘），
         * 并发只发生在识别阶段；页数 &lt;= 2 时始终串行。{@code format=md} 单页明显更慢，这里调高能补回来。
         */
        private int ocrParallelism = 2;
        /**
         * {@code understand_document} 分批理解 PDF 时的并发批数。批与批并发跑，但结果严格按批序
         * 归并（{@code QaMerge}），所以并发不影响输出顺序。串行时 72 页要 24 次 VL 调用，必然撞
         * 600s 预算；2 并发把它压到 ~100s。
         */
        private int understandParallelism = 2;
        /**
         * 抽取类任务最多 OCR 多少页（成本兜底）。超过则只识别前 N 页，并在 txt 末尾写明
         * 「共 X 页，只识别了前 N 页」，抽取结果只覆盖已识别的部分。
         */
        private int maxPages = 200;
        /** 栅格化最长边像素（控 token）；0=不缩放。pdftoppm -scale-to。 */
        private int maxImageDimension = 1568;
        /** 栅格化 JPEG 质量。 */
        private int jpegQuality = 85;
        /** 单次 VL 调用超时（秒）。 */
        private int timeoutSeconds = 120;
        /** PDF 下载体积上限（字节），防 DoS。 */
        private int maxDownloadBytes = 200 * 1024 * 1024;
        /**
         * TXT 分块大小（字符数），每块独立调模型抽取。按抽取端点的上下文定：视觉端点
         * n_ctx=131072，40000 个中日韩字符约 4 万 token，输入输出都留有余量。
         * 块越大越少「字段值正好落在块边界」和跨块互斥候选，代价是单次调用更慢。
         */
        private int textChunkSize = 40000;
        /** TXT 分块重叠（字符数）：只在没有页标记、退回字符窗口时才用得到。 */
        private int textChunkOverlap = 400;
        /**
         * SSRF 主机白名单（按后缀匹配，如 {@code internal.example}）；空=允许任意 http/https（与图片工具一致）。
         */
        private List<String> ssrfAllowlist = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPagesPerCall() {
            return pagesPerCall;
        }

        public void setPagesPerCall(int pagesPerCall) {
            this.pagesPerCall = Math.max(1, pagesPerCall);
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = Math.max(1, concurrency);
        }

        public int getOcrParallelism() {
            return ocrParallelism;
        }

        public void setOcrParallelism(int ocrParallelism) {
            this.ocrParallelism = Math.max(1, ocrParallelism);
        }

        public int getUnderstandParallelism() {
            return understandParallelism;
        }

        public void setUnderstandParallelism(int understandParallelism) {
            this.understandParallelism = Math.max(1, understandParallelism);
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = Math.max(1, maxPages);
        }

        public int getMaxImageDimension() {
            return maxImageDimension;
        }

        public void setMaxImageDimension(int maxImageDimension) {
            this.maxImageDimension = maxImageDimension;
        }

        public int getJpegQuality() {
            return jpegQuality;
        }

        public void setJpegQuality(int jpegQuality) {
            this.jpegQuality = jpegQuality;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxDownloadBytes() {
            return maxDownloadBytes;
        }

        public void setMaxDownloadBytes(int maxDownloadBytes) {
            this.maxDownloadBytes = maxDownloadBytes;
        }

        public List<String> getSsrfAllowlist() {
            return ssrfAllowlist;
        }

        public void setSsrfAllowlist(List<String> ssrfAllowlist) {
            this.ssrfAllowlist = ssrfAllowlist == null ? new ArrayList<>() : ssrfAllowlist;
        }

        public int getTextChunkSize() {
            return textChunkSize;
        }

        public void setTextChunkSize(int textChunkSize) {
            this.textChunkSize = Math.max(500, textChunkSize);
        }

        public int getTextChunkOverlap() {
            return textChunkOverlap;
        }

        public void setTextChunkOverlap(int textChunkOverlap) {
            this.textChunkOverlap = Math.max(0, textChunkOverlap);
        }
    }

    /**
     * 综合服务平台（IPS）统一配置：OCR / 印章 / 发票三个接口同一台服务、同一个基址。
     *
     * <ul>
     *   <li>{@code POST /api/v3/ocr/preprocess}      文字识别——抽取类任务的统一文本通道</li>
     *   <li>{@code POST /api/v3/seal/preprocess}     印章识别——技能 seal-recognition 经镜像内置 {@code ips seal}</li>
     *   <li>{@code POST /api/v3/invoice/preprocess}  发票识别——技能 invoice-recognition 经镜像内置 {@code ips invoice}</li>
     * </ul>
     *
     * <p>三者共用 {@code baseUrl} 与 {@code enabled}；分开的是各自超时，以及 OCR 特有的
     * 栅格化参数与表单参数。之前 OCR 单独一个 {@code agent.ocr} 配置块，等于把同一台服务
     * 配两遍，印章/发票还得再猜一次地址。
     */
    public static class Ips {
        private boolean enabled = true;
        /** 服务基址（不带路径）；留空=三个接口全部不可用。 */
        private String baseUrl = "http://127.0.0.1:8000";
        /** OCR 单次请求超时（秒）。一页一请求，这个值直接决定扫描件的时间上限。 */
        private int ocrTimeoutSeconds = 120;
        /** OCR 栅格化 dpi（0=用 pdftoppm 默认）。OCR 需要比视觉通道更高的分辨率。 */
        private int rasterizeDpi = 300;
        /** OCR 栅格化最长边像素上限（控上传体积）；0=不缩放（OCR 要保分辨率）。pdftoppm -scale-to。 */
        private int maxImageDimension = 0;
        /** OCR 栅格化 JPEG 质量。 */
        private int jpegQuality = 90;
        /** OCR 表单字段 resize：服务端是否缩放图像（true 更快）。 */
        private boolean resize = true;
        /** OCR 表单字段 use_cls：服务端是否跑方向检测模型（true 更慢）。 */
        private boolean useCls = false;
        /** OCR 表单字段 app_id：渠道标识，便于服务端统计；留空不下发。 */
        private String appId = "ai-agent-java";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getOcrTimeoutSeconds() {
            return ocrTimeoutSeconds;
        }

        public void setOcrTimeoutSeconds(int ocrTimeoutSeconds) {
            this.ocrTimeoutSeconds = Math.max(5, ocrTimeoutSeconds);
        }

        public int getRasterizeDpi() {
            return rasterizeDpi;
        }

        public void setRasterizeDpi(int rasterizeDpi) {
            this.rasterizeDpi = rasterizeDpi;
        }

        public int getMaxImageDimension() {
            return maxImageDimension;
        }

        public void setMaxImageDimension(int maxImageDimension) {
            this.maxImageDimension = maxImageDimension;
        }

        public int getJpegQuality() {
            return jpegQuality;
        }

        public void setJpegQuality(int jpegQuality) {
            this.jpegQuality = jpegQuality;
        }

        public boolean isResize() {
            return resize;
        }

        public void setResize(boolean resize) {
            this.resize = resize;
        }

        public boolean isUseCls() {
            return useCls;
        }

        public void setUseCls(boolean useCls) {
            this.useCls = useCls;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }
    }
}
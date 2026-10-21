# ai-agent-java — 设计与实现说明

> 本文描述**当前已实现并部署**的系统（AgentScope Java 版 Agent 任务服务）。
> 早期实现前的方案草稿、`pi` 子进程对比、JDK 工具链阻塞、待拍板决策点等已过时内容已移除，一律以本文与代码为准。

---

## 1. 目标与定位

对外提供 HTTP 接口：调用方提交自然语言 `instruction`（可选附带 JSON Schema 与技能名），服务端用 AgentScope `ReActAgent` 执行，返回**符合调用方 schema 的结构化结果**或文本。任务异步化、可轮询、可取消，状态与工作队列同源落在 MySQL。

核心设计取向：
- **框架内调用**：AgentScope 是 JVM 内 Java 库，无子进程、无临时文件、无文件轮询。
- **图片/文档与主模型解耦**：图像字节、PDF 栅格化页图只发到独立的视觉模型（`agent.vision`），**永不进入主模型上下文**；主模型只拿文字结果。
- **扫描页走 OCR 换文本**：PDF 里含图片的页（扫描件、插图页）与文字层几乎没有中日韩文字的页（缺 ToUnicode 的假文字层）逐页送 OCR（`agent.ips`）换文本，
  其余页直接取 `pdftotext` 文字层，之后的抽取/问答只发文本；OCR 未启用或连不上才回退视觉模型。
- **双模型档位**：`flash`（默认）/ `pro`，按任务 `model` 字段选用；两套完全独立配置。
- **工具自注册**：自定义工具通过 SPI 贡献，加/改工具只动一个模块（见 §4）。

---

## 2. 总体架构

```
调用方
   │ POST /api/v1/agent/task        GET /api/v1/agent/task/{id}     DELETE /task/{id}
   ▼
Spring Boot Web (AgentController)  ──►  AgentTaskService（提交/查询/取消/校验）
   │ code=0 + data.status=accepted       │ 提交时校验 model / skills / schema
   ▼                                     ▼
agent_task 表 = 状态存储 + 工作队列 (MySQL)
   ▲│  入队 INSERT status=accepted        │ claim = SELECT 候选 id → 条件 UPDATE 抢占(带租约)
   ▼│                                     ▼
TaskPoller：async-concurrency + sync-concurrency 两组常驻 worker，每线程 claim → run(续租) → claim
   ▼
AgentTaskRunner → ReActAgent.call(msgs, schemaNode, ctx)
   ├─ ModelFactory.build(modelName)   OpenAIChatModel → agent.models.<flash|pro>.base-url（本地 llama.cpp/vLLM）
   ├─ ToolkitFactory.build(taskDir)   框架内置工具(按开关) + List<AgentToolProvider> 遍历注册的自定义工具
   ├─ SkillRepositoryFactory          jar 内置 + 运维目录两级技能叠加
   └─ StructuredOutputConverter       按 profile 的 structured-output-mode 产出合规 JSON
   ▼
自定义工具 → VisionClient（图片）/ PdfService（PDF）→ agent.vision 视觉模型
                                    └─ PdfService 需要 OCR 的页 → IpsClient → agent.ips（OCR 接口）（换文本后仍走纯文本抽取）
```

结构化输出走两条路径之一（由 profile 的 `structured-output-mode` 决定：`auto|native|tool|two_phase|off`）：模型原生 `response_format`，或注入合成工具产出。

**two_phase 第二阶段的预算（flash 与 pro 同一套算法，2026-10-21 实测）**
- 输出预算＝`agent.models.<p>.structured-max-tokens`（留空＝沿用 `max-tokens`）。代码侧不再有写死的 2048 上限——输出被截断时 strata 端点直接回 **HTTP 502 `structured_output_failed`（"increase the output budget"）**，llama.cpp 回 **HTTP 200 带半截 JSON**（`finish_reason=length`），两种都在转换器里变成 null，任务侧只看到 `NO_RESULT: produced no value matching outputSchema`，真实原因在日志里是 `structured convert HTTP ...`。
- 输入预算＝端点 n_ctx：`schema token + 结果文本 token + structured-max-tokens` 必须小于它。实测中文字段约 **55 token/字段** 输入、**20 token/字段** 输出；60 字段 schema（4437 token）在 :8007（n_ctx **4096**）直接 400 `exceed_context_size_error`。
- 第二阶段的 HTTP 超时＝**任务剩余墙钟**（`AgentTaskRunner.phaseTimeout`，地板 60 秒），不再固定 120 秒：300 字段 schema 在 pro 上放开预算后实测 168 秒完成（输入 43621、输出 21020 token）。固定 120 秒会把这种能成的任务判成失败。
- 提交层还有一道 `AGENT_LIMIT_SCHEMA`（默认 8192 字符）：大 schema 先要放开它，`outputSchema exceeds N characters` 是提交期参数错误。

---

## 3. 模块结构（多模块 · 重构后）

```
ai-agent-java/                         Maven 父工程 (packaging=pom, groupId com.jtzj)
├── pom.xml                            父 pom：<modules> + 公共 properties(java17 / agentscope 2.0.3)
├── Dockerfile                         应用镜像（FROM base + COPY ai-agent-app/target/*.jar）
├── Dockerfile.base                    基础镜像（JDK+Python+poppler 等环境层，拆出来加速日常构建）
├── docker-compose.yml  .dockerignore  .gitignore  .env.example
│
├── ai-agent-core/                     【核心库】模型 + 配置 + 共享服务 + 工具 SPI（不依赖 AgentScope）
│   └── src/main/java/com/jtzj/agent/core/
│       ├── config/    AgentProperties        @ConfigurationProperties("agent")
│       ├── model/     AgentTaskRequest · TaskRecord · TaskStatus · TaskStatusView · TaskError · TaskUsage
│       ├── service/   VisionClient           独立视觉模型客户端（图片/扫描页字节只发到 agent.vision）
│       │              IpsClient             独立 OCR 服务客户端（multipart 上传页图，只回文本）
│       │              OcrTextService         图片/PDF → 同名 .txt（PDF 逐页按页序追加；txt 名带内容哈希）
│       │              IpsClient              印章/发票专用接口客户端（multipart，只回 JSON）
│       │              PdfService             整篇遍历 + 分批并发结构化抽取 + 确定性归并 + 页码溯源；理解类分批问全文再归并成一段
│       │                                   需要 OCR 的页走 OCR 文本通道，其余页取文字层
│       ├── support/   MediaInputs · Extraction · SandboxPaths · SourceFiles
│       │                                   入参解析 / 抽取归并 / 沙箱路径校验 / 来源落地（本地路径 + 内容哈希）
│       ├── error/     AgentException · ErrorCodes
│       └── spi/       AgentToolProvider · ToolContext             工具自注册接口
│
├── ai-agent-tools/                ★  【自定义工具】加/改工具只动这里；只依赖 core + agentscope
│   └── src/main/java/com/jtzj/agent/tools/    （每个工具类 + 同名 Provider，Provider 是 @Component）
│       ├── DocumentUnderstandTools  + DocumentUnderstandToolProvider  (understand_document)
│       ├── DocumentExtractTools     + DocumentExtractToolProvider     (extract_document_fields)
│       └── SystemTimeTools          + SystemTimeToolProvider          (get_system_time)
│       （印章 / 发票 / 身份证 / 营业执照不再是 Java 工具：改走内置技能 + 镜像里的 ips CLI，见 §12）
│
├── ai-agent-app/                    【Spring Boot 主应用】依赖 tools + core；启动时收集所有 provider
│   ├── pom.xml                      spring-boot-maven-plugin 在此；finalName=ai-agent-java-<ver>
│   ├── src/main/java/com/jtzj/agent/
│   │   ├── AgentApplication.java    主类（@ConfigurationPropertiesScan，扫描根 com.jtzj.agent）
│   │   ├── api/        AgentController · ApiResponse · ApiCodes · GlobalExceptionHandler
│   │   ├── runtime/    AgentTaskRunner · ToolkitFactory · ModelFactory ·
│   │   │               StructuredOutputConverter · SkillRepositoryFactory · TaskWorkspaceFactory
│   │   ├── service/    AgentTaskService · TaskPoller
│   │   ├── store/      MysqlTaskStore · TaskStore
│   │   └── config/     Jackson2Config（agentScopeObjectMapper，Jackson 2）
│   └── src/main/resources/  application.yml · db/{schema,migration-001..005}.sql
│
├── agent-skills/                    运行期技能挂载点（部署产物，默认只 README）
├── docs/  API.md · DEPLOY.md · DESIGN.md · examples/
└── scripts/  build.sh · docker-build*.sh · docker-run.sh · run-local.sh · deploy-remote.sh(本地,gitignore)
```

### 依赖方向（只向下）：`ai-agent-app → ai-agent-tools → ai-agent-core`

| 模块 | 职责 | 依赖 |
|---|---|---|
| `ai-agent-core` | 领域模型、配置、共享服务（视觉/PDF/沙箱）、错误、工具 SPI | Spring context + Jackson 2/3；**不依赖 AgentScope** |
| `ai-agent-tools` | 3 个自定义工具 + 各自 `AgentToolProvider` | core + `agentscope`（`@Tool/@ToolParam` 只在此出现） |
| `ai-agent-app` | REST API、任务服务、存储、编排（模型/工具/结构化/技能/工作区） | tools + core |

---

## 4. 工具自注册（SPI）

实现"加/改自定义工具只动 `ai-agent-tools` 一个模块"：

- `ToolkitFactory` **不再逐个 `new` 具体工具**，改为注入 `List<AgentToolProvider>` 遍历注册——它对"有哪些工具"零感知。
- 每个工具提供一个 `@Component` 的 `AgentToolProvider`：`name()` 是 kebab-case 标识（同时是开关 key），`createTool(ToolContext)` 从任务上下文取依赖：`taskDir()` / `vision()` / `pdf()` / `mapper()` / `properties()` / `bean(Class)`。
- **加一个工具 = 在 `ai-agent-tools` 加一个工具类 + 一个 Provider 类**，`ToolkitFactory` / `AgentProperties` / `application.yml` 均不改。新工具默认启用；要禁用加一行 `agent.tools.<name>: false`。
- 框架内置工具（shell / read-file / write-file / todo）仍由 app 内 typed 开关管理，并按任务的私有目录 `taskDir` 沙箱化。

---

## 5. 接口契约

### 统一响应信封
所有端点返回 `{ "code", "message"?, "data"? }`。**业务结果看 `code`，不看 HTTP 状态**：已处理的分支一律 HTTP 200（含参数错误、任务不存在等拒绝），仅未捕获的服务器异常才 HTTP 500 且 `code=9999`。任务本身失败不是接口失败——查询一个失败任务返回 `code=0`，失败详情在 `data.status` 与 `data.error`。

| `code`（ApiCodes） | 含义 |
|---|---|
| 0 `OK` | 成功 |
| 1001 `INVALID_REQUEST` | 请求非法（含未知 `model` 档位等，提交时拒绝） |
| 1002 `NOT_FOUND` | 未知端点 |
| 2001 `TASK_NOT_FOUND` | 任务不存在 |
| 2002 `TASK_NOT_CANCELABLE` | 任务当前状态不可取消 |
| 3001 `QUEUE_LIMITED` | 队列已满（`max-queued-tasks`） |
| 9999 `INTERNAL_ERROR` | 未捕获异常 |

### 任务生命周期 `TaskStatus`
`accepted → running → completed | failed | cancelled`（线格式小写）。

### 端点（前缀 `/api/v1/agent`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/task` | 提交任务；`options.sync=false`(默认) 立即返回 `status=accepted`，`sync=true` 阻塞至完成直接返回结果 |
| GET | `/task/{taskId}` | 查询状态/结果 |
| DELETE | `/task/{taskId}` | 取消（仅未开始的可取消，否则 2002） |
| GET | `/tools` | 列出**自定义工具**（当前 3 个，来自 SPI，不含框架内置） |
| GET | `/skills` | 列出已安装技能名 |
| GET | `/health` | 运行态：`model`/`asyncConcurrency`/`syncConcurrency`/`queued`/`queuedSync`/`active`/`syncActive`/`workerId`/`permissionMode`/`maxQueuedTasks`/`structuredMaxTokens` |

### 提交体 `AgentTaskRequest`
```json
{
  "taskId": "task-20260917-001",          // 可选，缺省服务端生成；同 taskId 幂等
  "instruction": "逐张抽取发票字段……\n<url1>\n<url2>",
  "model": "pro",                          // 可选，选 agent.models 档位；未知档位 → 1001
  "outputSchema": { "type":"object", "properties": { "...": {} } },
  "skills": ["report-format"],             // 可选，点名技能（服务端强制注入正文）
  "options": { "timeoutSeconds": 180, "sync": false },
  "metadata": { "projectId": "..." }
}
```
图片/文档来源以 URL 形式写在 `instruction` 文本里；任务启动前由 `SourceIngestor` 把其中的文件 URL 抓到 `<taskDir>/src/` 并把指令原地改写成本地相对路径，工具只吃本地路径（见 §11）。请求体不设专门的 images/files 字段。

### 结果视图 `TaskStatusView`
```json
{ "taskId":"…", "status":"completed",
  "resultText":"…",                         // 文本或按 outputSchema 产出的 JSON 字符串
  "usage": { "inputTokens":0, "outputTokens":0 },
  "createdAt":"…Z", "startedAt":"…Z", "completedAt":"…Z", "durationMs":1234 }
```
失败时附 `error`: `{ "code":"AGENT_TIMEOUT|AGENT_EXECUTION_FAILED|NO_RESULT|AGENT_INTERRUPTED|INVALID_REQUEST|CONCURRENCY_LIMITED", "message":"…", "retryable":true|false }`。

> 时间字段以 UTC 时刻（`...Z`）返回；库里的 `DATETIME` 列按 `Asia/Shanghai` 存北京墙钟（JDBC `serverTimezone=Asia/Shanghai`）。

---

## 6. 配置（`application.yml`，`agent.*`，全部可用同名环境变量覆盖）

| 键组 | 关键项 |
|---|---|
| `agent.default-model` | 默认档位（`flash`） |
| `agent.models.<flash\|pro>` | **两套完全独立**：`base-url` `api-key` `name` `max-tokens` `structured-max-tokens` `temperature` `context-window-size` `structured-output-mode` `reasoning-effort` `native-structured-output` `native-structured-output-with-tools` `strata-checkpoint` |
| `agent.vision` | 独立视觉模型：`base-url` `api-key` `name` `temperature` `reasoning-effort` `max-tokens` `max-images` `max-image-bytes` `timeout-seconds` |
| `agent.text-model` | 抽取/QA 的纯文本子调用用的 profile 名（`agent.models.*`），默认 `pro`；留空=回退视觉端点 |
| `agent.runner` | `name` `sys-prompt` `max-iters` `permission-mode`(默认 `BYPASS`) |
| `agent.execution` | `async-concurrency`(1) `sync-concurrency`(1) `default-timeout-seconds`(120) `max-timeout-seconds`(900) `lease-seconds`(90) `lease-reap-seconds`(30) `max-attempts`(2) `poll-interval-ms` `claim-peek` `max-queued-tasks`(1000) |
| `agent.tools.*` | 自定义工具按 provider 名开关（默认开）+ 框架内置 `shell/read-file/write-file/todo` + `working-dir` `shell-allowed-commands` `delete-workspace-on-finish` + 看图开关 `document-understand-vision`（`false` = `understand_document` 不看图，图片/PDF 走 OCR 文本） |
| `agent.pdfs` | `enabled` `pages-per-call` `concurrency` `ocr-parallelism` `understand-parallelism` `max-pages` `max-image-dimension` `jpeg-quality` `timeout-seconds` `max-download-bytes` `text-chunk-size` `text-chunk-overlap` `ssrf-allowlist` |
| `agent.ips` | 综合服务平台，Java 侧只剩 OCR：`enabled` `base-url` `ocr-timeout-seconds` `rasterize-dpi` `max-image-dimension` `jpeg-quality` `resize` `use-cls` `app-id`（OCR 的 format 写死：PDF 用 mix、图片用 md，不再配置）。印章/发票走镜像内置 `ips` CLI，其超时是 OS 变量 `IPS_TIMEOUT_SECONDS`（不在 Spring 配置里） |
| `agent.skills` | `enabled` `directory` `classpath-location` `code-execution-enabled` `max-requested` `max-inline-chars` |

数据源：`serverTimezone=Asia/Shanghai`（见 §5 时间说明）。

---

## 7. 并发、租约与崩溃恢复

- **并发=worker 线程数**：`async-concurrency` 个异步线程 + `sync-concurrency` 个同步线程，每线程同时跑 1 个任务，直接从 `agent_task` claim，不存在内存队列。
- **入队**：`INSERT status=accepted` 即完成入队。
- **抢占**：MySQL 5.7 无 `FOR UPDATE SKIP LOCKED`。改为「SELECT 候选 id → `UPDATE … WHERE task_id=? AND status='accepted'`」，靠 InnoDB 行锁保证只有一个 worker `affected=1`。候选排序 `created_at ASC, task_id ASC`，按提交先后保证公平。
- **两条通道，各自定量**：`async-concurrency` = 异步通道线程数，`sync-concurrency` = 同步通道线程数（默认各 1，每进程）。两条通道认领的行互斥：异步候选带 `sync_requested = 0`，同步候选带 `sync_requested = 1`。所以「同时跑几个异步」和「同时跑几个同步」是两个独立数字，异步长任务再久也堵不住同步调用。
  - 两条通道都常驻，循环结构完全一样，只是候选集不同（`agent-worker-N` / `agent-sync-worker-N`）。队列空时 worker 停在 `workSignal` 上，`submit()` 提交完行就 `notifyAll()` 叫醒 ⇒ **入队到开跑不等轮询**。
  - `max-poll-backoff-seconds`（默认 3s）退化为兜底超时，只覆盖「别的实例提交的」和「reaper 重入队的」两种情况；空闲查询频率仍是每 worker 每 3s 一条走 `idx_status_created` 的 SELECT，默认各 1 时合计约 0.67 qps。单机部署时这条兜底基本不会触发。
  - `sync-concurrency = 0` 关闭同步通道：异步候选改回 `sync_requested DESC` 优先认领同步行，保证同步行不会没人处理。
  - 两个数字都是**每进程**的：多实例部署时全局并发 = 实例数 ×（异步 + 同步）。要把「同时只跑一个同步」变成全局不变量，就得落到 DB（STORED 生成列 + 唯一索引，让第二条 `running & sync_requested=1` 直接撞唯一键），当前按单机资源有限的前提不做。
  - 同步任务之间按 `created_at` 排队：前一个是 72 页长任务时，后一个同步任务要等它跑完，调用方按自己的 HTTP 超时兜底。
- **租约/心跳**：claim 写 `lease_expires_at`（`lease-seconds`，默认 90s），执行期续租；进程被杀后租约过期，reaper（`lease-reap-seconds`）把 `running` 退回 `accepted` 重跑；累计 claim 达 `max-attempts`（默认 2）则 `failed`。租约过期比较走 Java 传参（`lease_expires_at < ?`），与时区无关。
- **背压**：队列深度超 `max-queued-tasks` 时新提交返回 `3001 QUEUE_LIMITED` 并回滚该行。
- **整体超时**：任务级 `timeoutSeconds`（默认 `default-timeout-seconds`，上限 `max-timeout-seconds`）→ `AGENT_TIMEOUT`。

---

## 8. 安全（现状与硬化）

- **权限模式**：默认 `BYPASS`，且框架内置 shell / 读写文件默认开启——但文件与 shell 的**工作目录被沙箱到该任务私有目录** `<working-dir>/<taskId>`，AgentScope 在执行期强制，越界拒绝。等于"沙箱内的远程命令执行"。
- **硬化手段**：收紧 `agent.tools.*`（关 shell/写文件）、把 `permission-mode` 调到 `EXPLORE`/更严格、配 `shell-allowed-commands` 白名单、`delete-workspace-on-finish` 控制产物留存。
- **shell 的实际风险不止「能跑命令」**：框架的 shell 工具对输出没有上限，模型 `curl` 一个大文件到 stdout 就等于把整个文件读进主模型上下文（实测 30MB PDF ≈ 13.7 万字符，直接撑爆并耗尽 max-iters）；白名单只按可执行文件名拦，拦不住这种输出。文档工具又不再吃 URL（文件在任务启动前已就绪在 `src/` 下），curl 没有任何正当用途，生产与测试环境均置 `AGENT_TOOL_SHELL=false`。
- **本地文件入参**：`SandboxPaths` 严格限制在任务沙箱内，越界拒绝；本地源文件不删除。
- **鉴权**：本服务**不含鉴权**（API Key/mTLS 由上游网关负责，接口对外前必须前置鉴权）。
- **注入面**：`outputSchema`/`skills` 会进 prompt，提交时校验（技能名白名单正则、数量与注入字符上限；未安装的技能名忽略并记 WARN，不报错）。

---

## 9. 技能（skills）接入

AgentScope 2.0.3 自带 `io.agentscope.core.skill`，格式与 Claude/pi skills 同构：`<dir>/<skill-name>/SKILL.md` + YAML frontmatter。

**接法**：`ReActAgent.Builder.skillRepositories(...)`（**不要**用已 `@Deprecated` 的 `skillBox(...)`）。挂上后 `build()` 自动安装 `DynamicSkillMiddleware`，在每次 `call()` 重写 `<available_skills>` 并注册 `load_skill_through_path`。

**两级来源（低→高，后者同名覆盖前者）**：jar 内置（`classpath-location`，缺目录静默跳过）+ 运维目录（`agent.skills.directory`，只读，按 SKILL.md 的 mtime/size 快照，**改文件不需重启**）。

**两种生效方式（关键取舍）**：请求带 `skills:[...]` → 服务端把技能正文直接注入任务提示、强制生效（点名=必须遵循，交模型自选是概率性的）；不带则只暴露 name/description，模型按需 `load_skill_through_path`。提交时校验技能名/数量/注入字符上限；未安装的技能名忽略并记 WARN（历史上是 400，会让还在传旧技能名的调用方直接失败）。

**成本**：装了技能时即使请求不用，`<available_skills>` 元数据也会增加每次请求 input（实测 2 个技能约 +600 token；空目录不注入空块）。显式点名的技能正文另计。彻底关掉：`AGENT_SKILLS_ENABLED=false`。

**目录分工**：`docs/examples/skills/` 是可运行示例；`agent-skills/` 是运维挂载点（默认只 README，内容属部署产物不入库）。

**抽取即终局（为什么禁止用 `understand_document` 补 null 字段）**：抽取子模型每次都遍历全部文本块并按语义匹配别名（`TextService` 的提示词），所以「换个字段名再抽一遍」和「用视觉补一处」都必然同解。早先 sys-prompt 里「字段 null 时可以用 understand_document 补这一处」是一条**授权**，模型据此把 2 页扫描件跑成 14 次抽取 + 6 次视觉（188s，字段其实全对）。现在三处一起收口：返回值里带 `null_note` 说明 null 即终局；sys-prompt 改成「抽取类任务调一次抽取工具就结束，绝不用 understand_document 补字段」；`DocumentExtractTools.Attempts` 按来源计数（`.ocr/extracted.json`，只存计数不存正文）超过 3 次直接拒绝。
**例外是 OCR 自己不完整**：有页失败时 txt 里写明缺口、产物改名带 `-partial`（不参与复用，下次重跑），
返回值换成 `partial_note`、去掉 `null_note`——否则一次瞬时的服务报错会被永久固化成「文档里没这个字段」。

**纯文本子调用不用视觉端点（`agent.text-model`）**：图片/PDF 一旦 OCR 成 txt，抽取和 QA 手上就只剩文本，此时调的是 `agent.vision` 那个 VL 模型，纯属将就。实测同一份营业执照的 OCR 文本（680 字符、单块、`temperature=0`）：字段列表给 6 个时稳定漏掉 `成立日期`（文本里明写 `成立日期 1989年09月09日`），只给 3 个就正常；同一提示词换成文本模型 `pro` 则 6/6 全对。所以 `askTextOnly` 默认改走 `agent.text-model`（默认 `pro`），只有真带图的请求才去视觉端点。

**工具幂等（2 个文档工具共用一份调用台账）**：模型会在一次任务里把同一个来源反复喂给同一个工具
（实测印章工具连调 4 次、`understand_document` 连调 5 次；印章工具现已撤下，见 §12），每次都是真实的 IPS/模型调用，而结论不会变。
台账落在任务目录 `.ocr/calls.json`：key＝工具名＋归一化参数，命中就直接返回上次的返回；失败不存结果（可重试）但照样计数，
否则模型对着一个一直失败的来源会无限重试；
understand 另有「一次任务最多真跑 4 次」的硬闸，extract 有「同一来源最多 3 次」。台账只存结构化结论（≤32000 字符），不存全文、
不常驻内存，随任务目录一起删——它是重复调用闸，不是内容缓存。`get_system_time` 不参与。

****印章/发票：工具 → 技能 → 又回到技能（这次成立）**：这条路径来回过一次，值得记清楚。
第一轮撤技能是因为技能正文会逼模型先 `curl -o` 下载文件，同一份内容出现「URL」和「本地副本」两个身份，
去重必然失效（实测为 1 个字段把 72 页重新 OCR 一遍、87s 撞超时），于是改成内置工具。
现在重新做成技能（`seal-recognition` / `invoice-recognition`，随 jar 内置）并删掉 Java 工具，前提条件已经变了：
§11 的 `SourceIngestor` 在任务启动前就把指令里的文件 URL 下载到 `src/` 并原地改写指令，工具与 CLI 都只吃本地路径，
`curl`/`wget` 也被命令黑名单直接拒——「两个身份」这个坑的成因被拆掉了。同时 `ips 1.0.4` 修好了 `seal` 不请求
`return_ocr_text` 的 bug（旧版 `ocr_result` 恒为空串，读不出公司名，这才是当初必须用 Java 工具的真实原因）。
收益是少一套口径：IPS 的字段名、错误文案、缓存行为只有一份，不会再出现「CLI 与工具结论不一致」。
上传侧也不整份读进内存：请求体先落到任务的 `.ocr/` 沙箱再用 `BodyPublishers.ofFile` 流式发送，
堆占用与文件大小无关（之前 `readAllBytes` + `ByteArrayOutputStream` + `toByteArray()` 三次完整副本，
一份 200MB 的 PDF 单次调用就能吃掉 600MB 堆）。SSRF 主机白名单也统一到 `SourceFiles.validateUrl`，
OCR 转文本、印章、发票、PDF 理解四条抓取通道都过它。

---

## 11. 文件来源预处理（URL → 本地文件）

指令里只会给 URL，而每条工具路径（OCR 转文本、印章、发票、PDF 理解、TXT 抽取）都要先把来源落地成文件。
之前是**每条路径各自下载**：一份 30MB 扫描件在一个「抽取 + 印章」的混合任务里被下载 3-4 遍，光下载 90s+；
模型自己 `curl -o` 落盘更糟——同一份内容出现「URL」和「本地副本」两个身份，按来源做的去重全部失效。

现在下载只发生一次，在 agent 启动之前（`SourceIngestor`，`ai-agent-app/runtime`）：

1. 扫指令里的 URL，只认扩展名白名单（pdf/jpg/jpeg/png/webp/gif/bmp/txt），按出现顺序去重；
   中文标点与 CJK 不算 URL 的一部分（「…pdf，抽取字段」必须在这里断开，否则逗号进 URL 必 404）。
2. 串行下载到 `<taskDir>/src/<序号>-<清洗后的文件名>`（保留中文、过滤非法字符、主干截 60 字符、扩展名小写）。
3. 写 `<taskDir>/.ingest/manifest.json`（url / path / bytes / sha256），事后能查「这个文件是从哪个 URL 来的」。
4. 指令里的 URL 原地换成 `src/...`，末尾追加一行就绪说明；喂给模型的是改写后的指令。

配套约束：

- **工具硬拒绝 URL**：`SourceFiles.fetch`、`MediaInputs`、`PdfService`、`TextService` 收到 URL 直接返回可执行错误
  （提示改用 `src/` 下的路径）。`PdfService`/`TextService`/`VisionClient` 里那三份重复的下载实现已删除，
  全工程只剩 `SourceFiles.download` 一处，且只由 `SourceIngestor` 调用。
- **抓不到文件就快速失败**：不启动 agent，`error.code=SOURCE_UNAVAILABLE`；4xx/超体积/空文件不重试，
  5xx/超时/网络错误可重试。
- **「必须调工具」的守卫仍按原始指令判定**：改写后的指令里已经没有 URL，若用改写后的文本判定，
  `DOCUMENT_REFERENCE` 会失效，模型凭记忆编字段那条防线就没了。
- **清理只有一个开关**：`agent.tools.delete-workspace-on-finish=true` 时整个任务目录（`src/`、`.ocr/`、
  `.ingest/`）一起删；false 时一律不删。之前「false 也要删 `.ocr/`」的额外逻辑已去掉——测试环境要的就是
  「跑完能进去看」，生产保持 true，否则每份扫描件在盘上留几十 MB 只涨不缩。

### 同步调用的 PDF 页窗口（`PageWindow`）

`options.sync=true` 时调用方正阻塞在 HTTP 里等结果，而 72 页扫描件光 OCR 就要 180-300s，必然顶到超时。
所以同步请求对 PDF 只取**首尾各 5 页**。裁剪做在**文件落盘那一刻**（`SourceIngestor` 下载完就地改写 `src/` 下的文件），
不在各工具里各裁一遍：

- 工具是大模型调的，来源身份只有「任务目录里的这个文件」一种。文件本身只剩 10 页，
  OCR、PDF 理解、`ips seal`/`ips invoice` CLI 全部自动只看这 10 页；不需要把「是不是同步」这个运行时信息
  透传进每条工具路径，也不会出现「工具 A 看了全文、工具 B 只看了首尾」这种同一任务内不一致。
- 阈值：页数 > 首尾之和（默认 5+5）才裁；`PageWindow.edgeSegments(total)` 给出 `1-5` 与 `total-4..total`。
- 实现用镜像里已有的 poppler：`pdfinfo` 取页数 → `pdfseparate -f/-l` 出分片 → `pdfunite` 合并 →
  `REPLACE_EXISTING` 覆盖原文件。分片写在 `src/` 下的临时目录，用完即删，不落沙箱外；不引 PDF 库。
- 裁完重算 `bytes`/`sha256`（清单里的哈希必须对得上盘上的文件），`manifest.json` 额外记 `pages_original`。
- 改写后的指令里显式写「原 72 页，现在只剩前 5 页与后 5 页」——否则模型会把「中间页没进文件」
  读成「全文没有这个字段」。取值口径不变（找不到写 null），但不许声称遍历过全文。
- 裁不动（坏 PDF、poppler 失败、页数读不到）就退回全文：一个可选优化不该判死整个任务，
  最差退化成改动前的耗时，日志里留 WARN。
- 实测（72 页 30MB 扫描件，同一份）：同步 24.4s、5 个字段全对；同一份走异步仍 72 页、207.7s、字段一致。

---

## 10. 构建与部署

- 编译：`mvn clean package`（根 reactor）→ `ai-agent-app/target/ai-agent-java-<version>.jar`（fat jar，`core`/`tools` 内嵌 `BOOT-INF/lib`，`Start-Class=com.jtzj.agent.AgentApplication`）。
- 镜像分层：`Dockerfile.base`（环境层：JDK+Python+poppler）与 `Dockerfile`（应用层 `FROM base` + COPY jar）拆分，日常改代码只重建应用层（秒级）。
- 应用层除 jar 外还 COPY `cli-tools/` 到 `/opt/agent-cli` 并加入 PATH（见 §12）；CLI 二进制跟仓库一起发牌，不该触发基础镜像重建。
- 部署：`BUILD=1 IMAGE=ai-agent-java:<ver> bash scripts/deploy-remote.sh`（编译 → 导出 tar → scp → 远端后台 load+run → 轮询健康）。`deploy-remote.sh` 含内网地址，**不入库**。
- Jackson 两套并存：模型用 Jackson 3（`tools.jackson`，Spring Boot 4 反序列化请求体），服务与 AgentScope 用 Jackson 2（`com.fasterxml`，bean 名 `agentScopeObjectMapper`）。

---

## 12. 镜像内置 CLI（`cli-tools/`）

`cli-tools/` 里放的是随应用一起发牌的静态二进制，打进镜像后位于 `/opt/agent-cli`（已加入 `PATH`），
agent 用 `execute_shell_command` 直接调。目前只有 `ips`（Rust/musl static-pie，不依赖 glibc，
在 `eclipse-temurin:17-jre-noble` 与 alpine 上均实测可跑，仅 x86-64）。

**为什么走 CLI 而不是再写一套内置工具**：IPS 有七类接口（idcard / bizlic / invoice / seal / ocr / card / handprint），
逐个做成内置工具就要再写七套 provider、响应解析、幂等。CLI 已经把 multipart 契约、错误信息、
结果缓存封装完成，新增一类能力只需换个子命令，零 Java 代码。

**契约（实测 `ips 1.0.5`）**

- 子命令：`ips <idcard|bizlic|invoice|seal|ocr|card|handprint> <文件> [--init-confidence F] [--timeout 秒] [--no-cache]`；
  `<文件>` 允许本地路径或 http(s) URL。标准输出就是接口原始 JSON（无包裹）。`--timeout` 是全局选项，
  默认 **180s**（`1.0.4` 及以前是 30s，多页 PDF 不够用，`1.0.5` 改的）。
- 失败：退出码 1，打印 `错误: 文件不存在: ...` / `错误: 下载失败: ... (HTTP 404)`。
- 环境变量：`IPS_BASE_URL`（必填，启动时缺失直接报错）、`IPS_CACHE_DIR`（默认 `$HOME/.ips/cache`）。
- 缓存：按「接口+来源」做 sha256 key，写单个 `.json`；命中时 ~0.1s。同一文件带不同查询串会存两份。

**边界（决定哪些该用它、哪些不该）**

- `ips seal` 从 `1.0.4` 起会请求 `return_ocr_text`：`ocr_result` 就是印章上的公司名（实测 `seal_1.jpg` →
  `重庆冠华建筑工程设备租赁有限公司`）。旧版恒为空串、读不出公司名——那才是当初必须写 Java 工具的真实原因，
  不是设计偏好。`ips invoice` 的 `ocr_text` 是**一个 JSON 字符串**（发票号码/购货方公司名称/发票总金额…要再解析一层）。
  两者图片和 PDF 都吃；不是中国增值税发票时接口回 400 + 纯文本「未检测到有效发票」，CLI 退出码 1、stdout 空——
  这是「不是发票」的权威结论，不是格式错误（英文 ACME 商业 invoice 就是这个返回）。
- `ips idcard` / `ips bizlic` 走 `/api/v3/{idcard,bizlic}/preprocess`，**只收图片**（v3 路由里只有
  `ocr` / `seal` / `invoice` 做了 PDF 分支），传 PDF 会失败。返回**恰好 1 个元素**，`ocr_text` 同样是
  JSON 字符串（身份证：姓名/性别/民族/出生/住址/公民身份号码/签发机关/有效期限；营业执照：社会统一信用代码/
  企业名称/类型/法定代表人/注册资本/成立日期/营业期限/住所/经营范围）。`idcard_type` 与 `bizlic_type` 都恒 1
  （检测模型不区分正反面/版本，只是证件类别），`corp_image_base64` 恒 null（CLI 固定 `return_corp_image=false`）。
  没检出时不返回空数组而是 400 +「未检测到有效身份证.」/「未检测到有效营业执照.」，退出码 1、stdout 空——
  同样是终局结论。服务端阈值默认 idcard 0.5、bizlic 0.8，CLI 不显式传就用服务端默认值。
  实测 `bizlic.jpg`（1.2MB）<1s：`bizlic_type=1`、`企业名称=中国交通物资有限公司`、
  `社会统一信用代码=91110000102027271X`、`注册资本=173443.17万元`、`经营范围=""`；同一张图喂 `ips idcard`
  → 退出码 1 +「未检测到有效身份证.」。
- **升级 CLI 必须清 `IPS_CACHE_DIR`**：缓存 key 只含「接口+来源」，不含 CLI 版本，旧版写过的 seal 条目
  （`ocr_result` 是空串）会被继续命中。
- `ips ocr` 能一把吃整份 PDF（服务端逐页，输出用 `--- 分页符 ---` 隔开），但它的输出会原样进主模型上下文；
  抽取类任务一律用 `extract_document_fields`（全文只落在任务目录的文本文件里，不进上下文）。
- 管道可用但受白名单约束：`DenyListCommandValidator` 按 `; | & 换行` 逐段判首个可执行文件，所以
  `ips seal src/x.jpg | head -c 500` 会因为 `head` 不在 `AGENT_SHELL_ALLOWED` 里被拒。直接读 JSON 输出最省事。
- CLI 虽然支持 URL，但工具层已经去远程化（§11）：指令里的 URL 会被改写成 `src/...`，因此也只给 CLI 传本地路径。

**接线**

- `Dockerfile`：`COPY cli-tools/ /opt/agent-cli/` + `chmod -R a+rX`，构建期跑 `ips --version` 与 `ips seal --help`
  （架构不对当场失败），再单独断言 `ips ocr` 退出码必须是 3（包装层没生效就构建失败），
  最后 `ENV PATH="/opt/agent-cli:${PATH}"`。
- **`cli-tools/ips` 是 sh 包装脚本，真二进制在 `cli-tools/native/ips`**（不在 PATH 上）。包装层只做一件事：
  按 `IPS_ALLOWED_SUBCOMMANDS`（默认 `idcard,bizlic,invoice,seal,card,handprint`，**不含 `ocr`**）放行子命令。
  为什么必须在这一层判：框架的 `UnixCommandValidator` 只比对命令的第一个 token，比对不到 `ips` 后面的子命令。
  退出码约定 `0` 成功 / `1` CLI 运行错误 / `2` clap 参数错误 / `3` 策略拒绝（刻意与 2 区分）。
  默认黑名单里的 `/opt/agent-cli/native/ips` 就是堵「模型用绝对路径直调未包装二进制、绕过子命令白名单」这条路；
  运维若自定义 `AGENT_SHELL_DENIED` 又漏掉这条，这个口子会重新打开。仍不做进程级隔离。
- **命令校验 `DenyListCommandValidator`**：框架自带的白名单在**非空时**会连带拒掉所有含 `& | ;` 与换行的
  命令（它只比对整条命令的第一个 token），混合任务就没法用，所以白名单非空时改由本类逐段判——按 `; | & 换行`
  拆段，每段首个 token（去引号、去路径、去脚本后缀、小写）都必须在 `agent.tools.shell-allowed-commands` 里，
  管道与组合命令因此照常可用；`./x` 仍走框架的「不得越出当前目录」检查，`../x` 直接拒。
  黑名单 `agent.tools.shell-denied-commands`（默认 `curl,wget,/opt/agent-cli/native/ips`）先于白名单判，
  条目两种写法：裸名按 basename 命中（`curl`、`/usr/bin/curl`、`"curl"` 都算），带路径的按路径后缀命中
  （`native/ips` 用来堵「白名单只写 `ips`、模型用绝对路径直调未包装二进制」）。被拒的命令不执行，模型收到
  `SecurityError: ...`，会据此改用工具或 `src/` 里的本地文件。
  要让 shell 除 `ips` 外读不到本地文件就配 `AGENT_SHELL_ALLOWED=ips`；文件工具是另一个开关（`AGENT_TOOL_READ`）。
  已知边界：白名单只看每段的**可执行文件**，不看参数，所以 `ips seal src/x.jpg > /tmp/o.txt` 这种重定向目标
  不在管控内（写比读危害小，且要堵就得解析重定向，代价不值）。真要硬隔离得靠容器文件系统权限，不在这一层。
- `scripts/docker-run.sh`：注入 `IPS_BASE_URL`（未显式给出时从同一个 `.env` 取 `AGENT_IPS_BASE_URL`，避免两处漂移）
  和 `IPS_CACHE_DIR=/workspace/.ips-cache`（在任务目录之外，不受 `delete-workspace-on-finish` 影响，跨任务复用）；
  启动后打印容器内 `ips --version` 做冒烟。
  取值优先级是 **shell 环境变量 > `.env` 同名键 > 脚本默认**（`pick`/`env_from_file` 两个函数），所以
  `IPS_BASE_URL` / `IPS_CACHE_DIR` / `IPS_TIMEOUT_SECONDS` / `IPS_ALLOWED_SUBCOMMANDS` 都可以直接写在 `.env` 里，
  不必再导 shell 变量；`IPS_ALLOWED_SUBCOMMANDS` 只在显式给出时注入，否则留给包装脚本用自己的默认。
  注意 `--env-file` 在 `-e` 之前，脚本这里再解析一遍并显式 `-e`，是为了让「`.env` 里写的值」一定赢过脚本默认。
- **sys-prompt 里不提 CLI**：早期有一份 `agent.runner.cli-hint` 在提示词里列可用子命令，已删除且不留开关。
  命令用法只写在技能正文（`skills/{seal,invoice,idcard,bizlic}-recognition`）里——两处写同一件事必然漂移。
- 超时：`ips 1.0.5` 起 CLI 自带 `--timeout` 默认 180s，包装脚本**不再补默认值**（早先它补 120 是为了救
  1.0.4 的 30s，现在留着反而会把 180 降级）。只有 `IPS_TIMEOUT_SECONDS` 显式给出时才追加 `--timeout`，
  `scripts/docker-run.sh` 默认从 `.env` 的 `AGENT_IPS_TIMEOUT` 注入——这就是被删掉的 `agent.ips.timeout-seconds`
  的新落点：Java 侧不再持有这个键，但两边超时仍同源。调用方显式写 `--timeout` 一律优先。
- 配了 `AGENT_SHELL_ALLOWED` 时要把 `ips` 加进白名单（否则 §6.2 的技能全跑不起来）；白名单为空则不限可执行文件。
  反过来，`AGENT_SHELL_ALLOWED=ips` 就是「shell 除 ips 外不能读本地文件」的配法，见上面校验器那条。

缓存只增不减，需要时清理：`docker exec ai-agent-java find /workspace/.ips-cache -mtime +30 -delete`。


## 13. 逐页 OCR 的并发结构（滑动窗口）

抽取类任务的 PDF 走「逐页 OCR → 同名 txt → 按块抽取」。逐页而不是一次性：一次请求换整份文本容易超时，
逐页则每页一次小请求，中途失败也已留下前面页的文本。

- 结构：按 `agent.pdf.pages-per-call` 分批**栅格化**，OCR 提交进 `agent.pdf.ocr-parallelism` 个 worker 的
  **滑动窗口**——栅格化与识别重叠，写盘严格按页序（`futures` 下标 = 页号-1；栅格化失败的页放占位 future
  保住这个对应关系，否则写盘会错位）。在途页上限 `3 × ocr-parallelism`，超过就按页序先写一段，避免整份
  PDF 的页图堆在内存里（200 页 × ~400KB）。
- 为什么不是「一批识别完再下一批」：实测 72 页扫描件（30MB）按 6 页一批 + 4 worker 跑，每批变成 4+2 两波，
  第二波 2 个槽空转，12 批白烧约 40s——配 4 并发实际只有 2.8 有效并发（单页 OCR mean 6.7s、串行总量 488.8s、
  墙钟 174.2s）。
- 改滑动窗口 + `ocr-parallelism=6` 后同一份文件：`ocr→txt pdf pages=72/72 failed=[] chars=60422` **118.7s**，
  任务 204.9s → **153.4s**，字段全对。IPS 服务端不是瓶颈（154KB 图 8 并发 wall 2.6s，近线性）。
- 对照视觉路径 `understand_document`：同一份 72 页扫描件 24 批 × 3 页、并发 2 → 126.0s。它一开始就把所有批
  提交进线程池（`PdfService.runBatches`），没有栅栏损耗——这正是当初「OCR 反而比视觉慢」的原因。
- 中断语义保留：每批开头查线程中断；已提交但未完成的页与未提交的页都写 `notRunPage`，随后 `shutdownNow()`。
- 配置：`AGENT_PDF_OCR_PARALLELISM` 建议 6（生产原值 2）。页数 <= 2 仍强制串行（`PARALLEL_ABOVE_PAGES`）。

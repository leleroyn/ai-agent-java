# ai-agent-java — 设计与实现说明

> 本文描述**当前已实现并部署**的系统（AgentScope Java 版 Agent 任务服务）。
> 早期实现前的方案草稿、`pi` 子进程对比、JDK 工具链阻塞、待拍板决策点等已过时内容已移除，一律以本文与代码为准。

---

## 1. 目标与定位

对外提供 HTTP 接口：调用方提交自然语言 `instruction`（可选附带 JSON Schema 与技能名），服务端用 AgentScope `ReActAgent` 执行，返回**符合调用方 schema 的结构化结果**或文本。任务异步化、可轮询、可取消，状态与工作队列同源落在 MySQL。

核心设计取向：
- **框架内调用**：AgentScope 是 JVM 内 Java 库，无子进程、无临时文件、无文件轮询。
- **图片/文档与主模型解耦**：图像字节、PDF 栅格化页图只发到独立的视觉模型（`agent.vision`），**永不进入主模型上下文**；主模型只拿文字结果。
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
TaskPoller：max-concurrent 个 worker 线程，每线程 claim → run(续租) → claim
   ▼
AgentTaskRunner → ReActAgent.call(msgs, schemaNode, ctx)
   ├─ ModelFactory.build(modelName)   OpenAIChatModel → agent.models.<flash|pro>.base-url（本地 llama.cpp/vLLM）
   ├─ ToolkitFactory.build(taskDir)   框架内置工具(按开关) + List<AgentToolProvider> 遍历注册的自定义工具
   ├─ SkillRepositoryFactory          jar 内置 + 运维目录两级技能叠加
   └─ StructuredOutputConverter       按 profile 的 structured-output-mode 产出合规 JSON
   ▼
自定义工具 → VisionClient（图片）/ PdfService（PDF）→ agent.vision 视觉模型
```

结构化输出走两条路径之一（由 profile 的 `structured-output-mode` 决定：`auto|native|tool|two_phase|off`）：模型原生 `response_format`，或注入合成工具产出。

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
│       │              PdfService             整篇遍历 + 分批并发结构化抽取 + 确定性归并 + 页码溯源
│       ├── support/   MediaInputs · Extraction · SandboxPaths     入参解析 / 抽取归并 / 沙箱路径校验
│       ├── error/     AgentException · ErrorCodes
│       └── spi/       AgentToolProvider · ToolContext             工具自注册接口
│
├── ai-agent-tools/                ★  【自定义工具】加/改工具只动这里；只依赖 core + agentscope
│   └── src/main/java/com/jtzj/agent/tools/    （每个工具类 + 同名 Provider，Provider 是 @Component）
│       ├── SystemTimeTools          + SystemTimeToolProvider          (get_system_time)
│       ├── ImageUnderstandTools     + ImageUnderstandToolProvider     (understand_image)
│       ├── ImageExtractTools        + ImageExtractToolProvider        (extract_image_fields)
│       ├── DocumentUnderstandTools  + DocumentUnderstandToolProvider  (understand_pdf)
│       └── DocumentExtractTools     + DocumentExtractToolProvider     (extract_pdf_fields)
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
│   └── src/main/resources/  application.yml · db/{schema,migration-001..003}.sql
│
├── agent-skills/                    运行期技能挂载点（部署产物；seal/invoice 两个环境特例已入库）
├── docs/  API.md · DEPLOY.md · DESIGN.md · examples/
└── scripts/  build.sh · docker-build*.sh · docker-run.sh · run-local.sh · deploy-remote.sh(本地,gitignore)
```

### 依赖方向（只向下）：`ai-agent-app → ai-agent-tools → ai-agent-core`

| 模块 | 职责 | 依赖 |
|---|---|---|
| `ai-agent-core` | 领域模型、配置、共享服务（视觉/PDF/沙箱）、错误、工具 SPI | Spring context + Jackson 2/3；**不依赖 AgentScope** |
| `ai-agent-tools` | 5 个自定义工具 + 各自 `AgentToolProvider` | core + `agentscope`（`@Tool/@ToolParam` 只在此出现） |
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
| GET | `/tools` | 列出**自定义工具**（当前 5 个，来自 SPI，不含框架内置） |
| GET | `/skills` | 列出已安装技能名 |
| GET | `/health` | 运行态：`model`/`maxConcurrent`/`queued`/`active`/`workerId`/`permissionMode`/`maxQueuedTasks` |

### 提交体 `AgentTaskRequest`
```json
{
  "taskId": "task-20260917-001",          // 可选，缺省服务端生成；同 taskId 幂等
  "instruction": "逐张抽取发票字段……\n<url1>\n<url2>",
  "model": "pro",                          // 可选，选 agent.models 档位；未知档位 → 1001
  "outputSchema": { "type":"object", "properties": { "...": {} } },
  "skills": ["invoice-recognition"],       // 可选，点名技能（服务端强制注入正文）
  "options": { "timeoutSeconds": 180, "sync": false },
  "metadata": { "projectId": "..." }
}
```
图片/文档来源以 URL 形式写在 `instruction` 文本里，由 Agent 自行调用工具；请求体不设专门的 images/files 字段。

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
| `agent.models.<flash\|pro>` | **两套完全独立**：`base-url` `api-key` `name` `max-tokens` `temperature` `context-window-size` `structured-output-mode` `reasoning-effort` `native-structured-output` `native-structured-output-with-tools` |
| `agent.vision` | 独立视觉模型：`base-url` `api-key` `name` `temperature` `reasoning-effort` `max-images` `max-image-bytes` `timeout-seconds` |
| `agent.runner` | `name` `sys-prompt` `max-iters` `permission-mode`(默认 `BYPASS`) |
| `agent.execution` | `max-concurrent`(4) `default-timeout-seconds`(120) `max-timeout-seconds`(900) `lease-seconds`(90) `lease-reap-seconds`(30) `max-attempts`(2) `poll-interval-ms` `claim-peek` `max-queued-tasks`(1000) |
| `agent.tools.*` | 自定义工具按 provider 名开关（默认开）+ 框架内置 `shell/read-file/write-file/todo` + `working-dir` `shell-allowed-commands` `delete-workspace-on-finish` |
| `agent.pdf` | `enabled` `pages-per-call` `concurrency` `max-pages` `max-image-dimension` `jpeg-quality` `timeout-seconds` `max-download-bytes` |
| `agent.skills` | `enabled` `directory` `classpath-location` `code-execution-enabled` `max-requested` `max-inline-chars` |

数据源：`serverTimezone=Asia/Shanghai`（见 §5 时间说明）。

---

## 7. 并发、租约与崩溃恢复

- **并发=worker 线程数**：`max-concurrent` 个线程，每线程同时跑 1 个任务，直接从 `agent_task` claim，不存在内存队列。
- **入队**：`INSERT status=accepted` 即完成入队。
- **抢占**：MySQL 5.7 无 `FOR UPDATE SKIP LOCKED`。改为「SELECT 候选 id → `UPDATE … WHERE task_id=? AND status='accepted'`」，靠 InnoDB 行锁保证只有一个 worker `affected=1`。
- **租约/心跳**：claim 写 `lease_expires_at`（`lease-seconds`，默认 90s），执行期续租；进程被杀后租约过期，reaper（`lease-reap-seconds`）把 `running` 退回 `accepted` 重跑；累计 claim 达 `max-attempts`（默认 2）则 `failed`。租约过期比较走 Java 传参（`lease_expires_at < ?`），与时区无关。
- **背压**：队列深度超 `max-queued-tasks` 时新提交返回 `3001 QUEUE_LIMITED` 并回滚该行。
- **整体超时**：任务级 `timeoutSeconds`（默认 `default-timeout-seconds`，上限 `max-timeout-seconds`）→ `AGENT_TIMEOUT`。

---

## 8. 安全（现状与硬化）

- **权限模式**：默认 `BYPASS`，且框架内置 shell / 读写文件默认开启——但文件与 shell 的**工作目录被沙箱到该任务私有目录** `<working-dir>/<taskId>`，AgentScope 在执行期强制，越界拒绝。等于"沙箱内的远程命令执行"。
- **硬化手段**：收紧 `agent.tools.*`（关 shell/写文件）、把 `permission-mode` 调到 `EXPLORE`/更严格、配 `shell-allowed-commands` 白名单、`delete-workspace-on-finish` 控制产物留存。
- **本地文件入参**：`SandboxPaths` 严格限制在任务沙箱内，越界拒绝；本地源文件不删除。
- **鉴权**：本服务**不含鉴权**（API Key/mTLS 由上游网关负责，接口对外前必须前置鉴权）。
- **注入面**：`outputSchema`/`skills` 会进 prompt，提交时校验（技能名白名单正则、数量与注入字符上限、未知技能直接拒绝而非静默忽略）。

---

## 9. 技能（skills）接入

AgentScope 2.0.3 自带 `io.agentscope.core.skill`，格式与 Claude/pi skills 同构：`<dir>/<skill-name>/SKILL.md` + YAML frontmatter。

**接法**：`ReActAgent.Builder.skillRepositories(...)`（**不要**用已 `@Deprecated` 的 `skillBox(...)`）。挂上后 `build()` 自动安装 `DynamicSkillMiddleware`，在每次 `call()` 重写 `<available_skills>` 并注册 `load_skill_through_path`。

**两级来源（低→高，后者同名覆盖前者）**：jar 内置（`classpath-location`，缺目录静默跳过）+ 运维目录（`agent.skills.directory`，只读，按 SKILL.md 的 mtime/size 快照，**改文件不需重启**）。

**两种生效方式（关键取舍）**：请求带 `skills:[...]` → 服务端把技能正文直接注入任务提示、强制生效（点名=必须遵循，交模型自选是概率性的）；不带则只暴露 name/description，模型按需 `load_skill_through_path`。提交时校验技能名/数量/注入字符上限，未知技能直接拒绝并回列可用清单。

**成本**：装了技能时即使请求不用，`<available_skills>` 元数据也会增加每次请求 input（实测 2 个技能约 +600 token；空目录不注入空块）。显式点名的技能正文另计。彻底关掉：`AGENT_SKILLS_ENABLED=false`。

**目录分工**：`docs/examples/skills/` 是可运行示例；`agent-skills/` 是运维挂载点（默认只 README，内容属部署产物不入库——**例外**：`seal-recognition`、`invoice-recognition` 两个环境特例技能已刻意入库）。

---

## 10. 构建与部署

- 编译：`mvn clean package`（根 reactor）→ `ai-agent-app/target/ai-agent-java-<version>.jar`（fat jar，`core`/`tools` 内嵌 `BOOT-INF/lib`，`Start-Class=com.jtzj.agent.AgentApplication`）。
- 镜像分层：`Dockerfile.base`（环境层：JDK+Python+poppler）与 `Dockerfile`（应用层 `FROM base` + COPY jar）拆分，日常改代码只重建应用层（秒级）。
- 部署：`BUILD=1 IMAGE=ai-agent-java:<ver> bash scripts/deploy-remote.sh`（编译 → 导出 tar → scp → 远端后台 load+run → 轮询健康）。`deploy-remote.sh` 含内网地址，**不入库**。
- Jackson 两套并存：模型用 Jackson 3（`tools.jackson`，Spring Boot 4 反序列化请求体），服务与 AgentScope 用 Jackson 2（`com.fasterxml`，bean 名 `agentScopeObjectMapper`）。

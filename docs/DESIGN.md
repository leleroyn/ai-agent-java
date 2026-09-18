# AgentScope 版 Agent 接口服务 — 实施方案（待审查）

> 目标：对外提供 HTTP 接口，把自然语言指令交给 Agent 执行，返回符合调用方给定 JSON Schema 的结构化结果。
> 本方案基于**实测结论**改写你原方案（pi spawn）中不成立/不必要 parts。**未开工，等确认。**

---

## 0. 实测结论（先看这个，有阻塞）

| 项 | 实测方式 | 结果 |
|---|---|---|
| AgentScope Java 坐标 | 查私服/阿里云 maven-metadata | `io.agentscope:agentscope-core:2.0.3`，私服 `192.168.2.190:8081` **可解析** |
| 字节码版本 | 解包 core jar，读 400 个 class 的 major version | 全部 `major=61` → **Java 17** |
| 官方要求 | `java.agentscope.io/v2/en/docs/quickstart.md` | "requires **JDK 17 or newer**, Maven 3.9+ recommended" |
| 本机 JDK | `java -version` + 扫盘 | 仅 **Java 8**（`jre1.8.0_503`、`.jdks/semeru-1.8.0_482`）；**无 17+ 的 `javac`** |
| IDEA JBR | `IntelliJ IDEA 2026.1.4/jbr/bin` | OpenJDK **25**，但**只有 `java` 没有 `javac`** → 能跑不能编 |
| Maven | `which mvn` | PATH 上无；仅 IDEA 自带 `maven3`（可用） |
| 私服 | curl | `mirrorOf=*` → agentscope 2.0.3、Spring Boot 3.5.16 / 4.0.8 / 4.1.1 均有 |
| 可用模型 | `~/.pi/agent/models.json` | 无云端凭证；本地 vLLM **OpenAI 兼容** `http://192.168.1.250:8009/v1`（`qwen`）、`8008`（`qwenvl`） |
| Docker | `docker version` | 29.6.1 可用，配 daocloud 加速 |

### ⛔ 唯一硬阻塞
**没有 JDK 17+ 的编译工具链。** Java 8 下 AgentScope 既编译不了也运行不了（UnsupportedClassVersionError）。必须先解决，见 §6 决策点 1。

---

## 1. 架构：AgentScope 让原方案大幅简化

关键区别：**pi 是外部 Node CLI（必须 spawn 子进程 + 文件交接），AgentScope 是 Java 库（JVM 内直接调用）。**

| 你原方案（pi） | AgentScope 方案 | 说明 |
|---|---|---|
| spawn `pi` 子进程 | JVM 内 `ReActAgent.call(...)` | 无进程管理、无 stdin/stdout JSONL 解析 |
| 临时目录 + `schema.json` + `result.json` | `call(msgs, SchemaClass.class)` | 无临时文件 |
| `-t read,write,bash,report_result`（漏 `report_result` 就静默无结果） | `Toolkit` 注册工具 | 无此坑 |
| 读 `result.json` 反序列化 | `msg.getStructuredData(X.class)` | 框架已解析 |
| 手写 `extension_ui` 自动取消防阻塞 | `PermissionMode.DONT_ASK` / `BYPASS` | **框架原生**（文档："DONT_ASK — Demote ASK to DENY，Unattended/scheduled runs"） |
| 每任务一个进程 | 线程池 + `Semaphore` 限流 | 见决策点 3 |
| `--no-session` 防会话污染 | `RuntimeContext(sessionId,userId)` + `AgentStateStore` | 会话可控可持久 |

结构化输出是框架内置能力（文档 `building-blocks/agent.md` §Structured Output），两条路径自动选：

| 路径 | 条件 | 行为 |
|---|---|---|
| Native | 模型支持带工具的 `response_format`（OpenAI/DashScope） | schema 走 `response_format`，模型保证合法 JSON |
| Fallback | 模型不支持（Anthropic/Ollama 等） | 自动注入合成工具 `generate_response`，模型调它产出结果 |

> 即：原方案用 `report_result` 工具解决的"如何让 Agent 输出指定 JSON"，AgentScope 已内置且调用方代码统一。
> ⚠️ 本地 vLLM(`qwen`) 走哪条路径**未实测**；`OpenAIChatModel` 有 `.nativeStructuredOutputWithTools(false)` 开关，实测后定值。

### 架构（替换原图）

```
调用方(企业系统)
   │ POST /api/v1/agent/task              GET /api/v1/agent/task/{id}
   ▼
Spring Boot Web 层  ──鉴权──►  任务服务 AgentTaskService
   │ 202 + taskId                          │
   ▼                                       ▼
agent_task 表 = 状态存储 + 工作队列 (MySQL 5.7)                
                                          ▲│
              入队=INSERT status=accepted  ││  claim=条件 UPDATE 抢占
                                          ▼│
                                   TaskPoller：N 个 worker 线程
                                   每线程 claim → run(续租) → claim
                                          ▼
                                   ReActAgent.call(msgs, schemaClass, ctx)
                                          │ Toolkit: 业务工具(可选) / 文件工具(默认关)
                                          ▼
                              OpenAIChatModel ──► vLLM http://192.168.1.250:8009/v1
```

无子进程、无临时文件、无文件轮询。

---

## 2. 接口契约（沿用你的设计，不破坏）

### 提交 `POST /api/v1/agent/task`
```json
{
  "taskId": "task-20260917-001",
  "instruction": "分析当前项目的代码质量并生成报告",
  "outputSchema": { "type": "object", "properties": {"summary": {"type": "string"}, "score": {"type": "number"}}, "required": ["summary","score"] },
  "options": { "timeoutSeconds": 120, "sync": false },
  "metadata": { "projectId": "proj-123", "callbackUrl": "https://internal/callback" }
}
```
→ `202`：`{ "taskId": "...", "status": "accepted" }`

- `taskId` 可选，缺省服务端生成；**同 taskId 重复提交 = 幂等**（复用/拒绝，见决策点 7）。
- `options.sync=true` → 阻塞至完成直接返回结果（`200`）；默认 false 走轮询。

### 查询 `GET /api/v1/agent/task/{taskId}`
```json
{ "taskId": "...", "status": "completed",
  "result": { "summary": "...", "score": 78.5 },
  "usage": { "inputTokens": 5120, "outputTokens": 310, "costEstimate": 0.0 },
  "completedAt": "2026-09-17T10:32:00Z", "durationMs": 118000 }
```
失败：
```json
{ "taskId": "...", "status": "failed",
  "error": { "code": "AGENT_TIMEOUT", "message": "...", "retryable": true } }
```
`status`：`accepted | running | completed | failed | cancelled`（沿用你的枚举）

其他：`DELETE /task/{id}` 取消；`GET /health`。

错误码：`AGENT_TIMEOUT` / `AGENT_EXECUTION_FAILED` / `SCHEMA_VIOLATION` / `CONCURRENCY_LIMITED` / `INVALID_REQUEST`

---

## 3. 模块与文件清单

```
ai-agent-java/
├── pom.xml
├── docs/DESIGN.md                        ← 本文件
└── src/main/java/com/example/agent/
    ├── AgentApplication.java
    ├── api/
    │   ├── AgentController.java          REST 层，只做参数校验 + 委派
    │   └── GlobalExceptionHandler.java
    ├── model/                            DTO（record）
    │   ├── AgentTaskRequest.java
    │   ├── TaskSubmitResponse.java
    │   ├── TaskStatusView.java
    │   ├── TaskStatus.java               枚举 + 状态常量
    │   ├── TaskError.java
    │   └── TaskUsage.java
    ├── service/
    │   ├── AgentTaskService.java         提交/查询/取消/幂等
    │   ├── AgentExecutor.java            ReActAgent 装配 + call + 超时 + 取结构化结果
    │   └── ConcurrencyLimiter.java       Semaphore 封装（背压：排队 or 拒绝）
    ├── store/
    │   ├── TaskStore.java                接口 save/get/cas
    │   ├── InMemoryTaskStore.java        默认（TTL 清理，单机）
    │   └── RedisTaskStore.java           可选，按决策点 4 决定是否启用
    ├── agent/
    │   ├── AgentModelFactory.java        OpenAIChatModel + baseUrl（可配置到本地 vLLM）
    │   ├── ToolkitFactory.java           注册业务工具；默认**不给** bash/write
    │   └── tool/                         业务工具示例（@Tool），如查询工单/查库
    └── config/
        ├── AgentProperties.java          @ConfigurationProperties("agent")
        └── AsyncConfig.java              线程池
src/main/resources/application.yml
```

核心执行逻辑（伪代码，体现关键差异）：
```java
ReActAgent agent = ReActAgent.builder()
    .name("task-agent")
    .sysPrompt(sysPrompt)
    .model(modelFactory.build())              // OpenAIChatModel → baseUrl 指本地 vLLM
    .toolkit(toolkitFactory.build())          // 业务工具，默认无 shell
    .permissionContext(PermissionContextState.builder()
        .mode(PermissionMode.DONT_ASK)        // 无头：ASK 自动降级 DENY，不会永久阻塞
        .build())
    .build();

Msg result = agent.call(List.of(new UserMessage(instruction)), outputSchemaNode, rtCtx)
                 .timeout(Duration.ofSeconds(timeoutSeconds))
                 .block();                    // 由 worker 线程承载，不占 Tomcat 线程

Object data = result.getMetadata().get("_structured_output");   // 或 getStructuredData(Class)
```

---

## 4. 并发、超时、可观测

| 项 | 做法 |
|---|---|
| 并发上限 | N 个 worker 线程（默认 N=4），每线程同时只跑 1 个任务，从 `agent_task` 直接 claim，因此并发数就是线程数，不存在内存队列 |
| 入队 | `INSERT` 一条 `status=accepted` 即完成入队；`POST` 立刻返回 202 |
| 抢占 | MySQL 5.7 无 `FOR UPDATE SKIP LOCKED`（8.0.1+ 才有）。改为「SELECT 候选 id → `UPDATE ... WHERE task_id=? AND status='accepted'`」，靠 InnoDB 行锁保证只有一个 worker 拿到 `affected=1` |
| 崩溃恢复 | 每次 claim 带租约（`lease_expires_at`，默认 90s），执行期间每 lease/3 续租。进程被杀后租约过期，reaper 把任务退回 `accepted` 重跑；累计 claim 次数达 `max-attempts`（默认 2）则置 `failed/AGENT_LEASE_EXPIRED` |
| 背压 | 队列深度超过 `max-queued-tasks`（默认 1000）时新提交返回 `429 CONCURRENCY_LIMITED` 并回滚该行 |
| 整体超时 | `Mono.timeout(...)` → `AGENT_TIMEOUT`，`retryable=true` |
| LLM 重试 | 框架/transport 层重试；本服务只做一次任务级重试（可配，默认 0） |
| Token/耗时 | 从 `Msg` usage 取，写进 `TaskStatusView.usage`；`durationMs` 服务端记录 |
| 日志 | 每任务 MDC 打 `taskId`；工具调用耗时打点 |
| 审计 | 保留最终 assistant 文本 + 结构化结果（大小上限截断），可配开关 |

---

## 5. 安全（必须你确认，接口对外即攻击面）

1. **鉴权**：接口给外部系统调用 → 必须有认证（API Key / mTLS / OAuth2）。**默认实现只留 API Key 拦截器占位，不猜你的鉴权体系。**
2. **工具授权 = RCE 风险**：`bash`/`write` 一旦开放，等于把远程命令执行交给调用方。
   - **默认：不开 shell/写文件**，只给业务只读工具。
   - 若必须给文件/shell：`PermissionMode.DONT_ASK` + 工作目录限制 + deny 规则（文档指出 deny 与危险路径检查**不可绕过**）。
3. **schema 注入**：`outputSchema` 由调用方传入并注入 system prompt，需限制大小（默认 ≤8KB）与嵌套深度。
4. `instruction` 长度上限（默认 ≤32KB）。

---

## 6. 需要你拍板的决策点

| # | 决策 | 选项 | 我的建议 |
|---|---|---|---|
| **1** | **JDK 17+ 工具链** ⛔ | (a) 本机装 JDK 21 LTS（无 winget/choco，需手动下载安装包）(b) 用 Docker 构建+运行（已可用，有加速）(c) 你已有 JDK 路径告诉我 | **先 (b) 跑通，再 (a)**；或你直接给 JDK 路径 |
| 2 | Spring Boot 版本 | (a) **4.0.8** + `agentscope-spring-boot-starter`（starter 原生依赖 SB 4.0.1）(b) **3.5.16** + 手动装配 AgentScope Bean | 你已有 SB 版本就跟随；新起项目选 **(a)** |
| 3 | 隔离粒度 | (a) 进程内调用（快、省内存，单任务 OOM/死循环影响同 JVM）(b) 每任务子进程跑一个 AgentScope runner jar（物理隔离，代价是启动开销+打包复杂度） | **(a)**；确需强隔离再上 (b) |
| 4 | 任务状态存储与队列 | (a) 内存队列（重启丢队列，且多实例无法共享）(b) Redis Stream/List (c) MySQL 表兼作队列 | **(c)**。MySQL 已在用，状态与队列同源，无「队列说在跑、库里说没跑」的一致性缝隙，也不引入新组件；代价是空轮询开销与需要租约机制（已实现并实测）|
| 5 | 满载策略 | (a) 排队 `accepted` (b) `429` 快速拒绝 | **(a)** 排队 + 队列上限 |
| 6 | 模型 | 先用本地 `qwen@192.168.1.250:8009` 跑通？ | **是**；无云凭证，且它 OpenAI 兼容可直接接 |
| 7 | 幂等语义 | 同 `taskId` 重复提交：(a) 返回已有状态（幂等）(b) `409` 拒绝 | **(a)** |
| 8 | 同步模式 | 要不要 `options.sync=true` 阻塞返回 | 要（成本低，复用同一执行路径） |
| 9 | 业务工具 | 第一批要暴露哪些工具？（如"查工单""查库"）还是先空 `Toolkit` 只做纯推理 | **先空 Toolkit** 打通链路，再加工具 |

---

## 7. 实现顺序（批准后）

1. `pom.xml` + `AgentApplication` + `application.yml`，**先用 `curl` 直连 vLLM 验证 `baseUrl` 可达**
2. `AgentModelFactory` + 一个 `main` 冒烟：`call(msgs, JsonNode schema)` → 确认结构化输出真实产出（**这是最大技术风险，优先验证**）
3. `TaskStore` + `AgentTaskService` + `AgentExecutor` + `ConcurrencyLimiter`
4. `AgentController` + DTO + 异常处理
5. 端到端实测：提交/轮询/超时/取消/幂等/并发满载，每条给实测输出
6. 按决策点补 Redis、鉴权、业务工具

---

## 8. 我明确不做的事

- 不猜你的鉴权/网关/部署形态；鉴权留占位并说明。
- 不默认开放 `bash`/文件写（见 §5）。
- 不引入原方案的 pi 依赖、`@matthewlam/pi-worker`、临时文件交接。
- 不在验证前宣称"应该没问题"——每步给实测输出。
- 顺带说明：之前为核查 pi 方案，我用 `pi install npm:@matthewlam/pi-worker` 装了该扩展（写在 `~/.pi/agent/settings.json` 的 `packages`）。切到 AgentScope 后它无用，**要不要我卸载？**

---

## 9. 技能（skills）接入【已实现并实测】

AgentScope 2.0.3 自带 `io.agentscope.core.skill`，格式与 Claude/pi skills 同构：`<dir>/<skill-name>/SKILL.md` + YAML frontmatter（`name`、`description`），可选 `references/`、`scripts/` 等附属文件。

**接法**：用 `ReActAgent.Builder.skillRepositories(...)`，**不要用 `skillBox(...)`**——后者在 2.0 已 `@Deprecated` 且官方注明与新仓库方式未一起测试。挂上仓库后 `build()` 自动安装 `DynamicSkillMiddleware`，它在每次 `call()` 的 system prompt 阶段重写 `<available_skills>` 块，并在 toolkit 上注册 `load_skill_through_path`。

**两个技能来源，按低→高优先级叠加**（后者同名覆盖前者，因此可以在不重新打镜像的情况下改写内置技能）：

| 来源 | 配置 | 说明 |
|---|---|---|
| jar 内置 | `agent.skills.classpath-location`（默认 `skills`） | 目录不存在时静默跳过，属正常情况 |
| 运维目录 | `agent.skills.directory`（默认 `./agent-skills`） | 只读挂载；按 SKILL.md 的 mtime/size 快照，**改文件不需重启**（已实测：标记由 v1 改 v2 后立即生效） |

**两种生效方式，这是本次实现的关键取舍**：

| 请求 | 行为 | 理由 |
|---|---|---|
| 带 `skills: [...]` | **服务端把技能正文直接注入任务提示**，强制生效 | 交给模型自己调 `load_skill_through_path` 是概率性的：本地 qwen 上同一条指令，一次调了并输出技能要求的标记，下一次直接跳过工具自由发挥。调用方显式点名=「必须遵循」，就该由服务端保证。实测注入后连续 3 次 3/3 命中，崩溃重启后由新实例领取的 3 个任务也 3/3 命中 |
| 不带 `skills` | 只暴露 name + description，模型按需 `load_skill_through_path` | 保留渐进披露，未用的技能几乎不花 token |

**校验（提交时，不是执行时）**：技能名必须匹配 `[A-Za-z0-9][A-Za-z0-9._+-]{0,63}`（技能名会进 prompt 和工具参数，同 taskId 白名单的理由）；数量 ≤ `max-requested`(8)；注入正文总量 ≤ `max-inline-chars`(12000)；**未安装的技能名直接 400 并回列可用清单**，而不是静默忽略——静默忽略会让 agent 在"调用方以为加了规范"的状态下自信作答。

**安全**：`code-execution-enabled` 默认 **false**。打开后 prompt 会加 `<files-root>` 并要求模型用 shell 跑技能脚本，在没有鉴权的服务上等于把执行面从"指令说什么"扩大到"谁能往技能目录写什么"。运维目录以 `writeable=false` 挂载，服务自身永不写技能。

**成本（实测量化）**：装了 2 个技能时，即使请求不使用技能，每次请求的 input 也会因 `<available_skills>` 元数据增加约 600 token（同一道"1+1"题：2566 vs 空目录 1956）。**技能目录为空时 AgentScope 不注入空块**，实测 1956 token 回到未启用 skill 功能的基线（约 1930），因此不需要额外加"无技能则不挂载"的判断。技能正文被显式指定时另计，本例 report-format 约 300 token。彻底关掉用 `AGENT_SKILLS_ENABLED=false`。

**目录分工**：`docs/examples/skills/` 是可运行示例（含两个带互斥结尾标记的对照技能，用于验证过滤器真的生效），`agent-skills/` 是运维挂载点、**默认只含 README**、内容属部署产物不入库。把示例拷进运维目录即可试用，**不需要重启**：`cp -r docs/examples/skills/report-format agent-skills/` 后 `GET /health` 立刻列出该技能，紧接着的任务就能应用它（均已实测）。

**落库**：`agent_task.skill_names`（逗号分隔，`NULL`=全部可见），见 `db/migration-002-skill-names.sql`。必须落库——队列在 MySQL 里，重启后由别的进程领取任务时要知道当初选了哪些技能。

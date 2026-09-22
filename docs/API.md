# ai-agent-java 接口文档

自然语言任务接口：外部系统提交一段自然语言指令，服务端用 LLM Agent 执行（可调用 shell / 读写文件 / 待办等工具），返回结构化 JSON 结果。

- **Base URL**：`http://<host>:8080/api/v1/agent`
- **请求/响应**：`Content-Type: application/json`
- **字符编码**：UTF-8。含中文的指令请确保请求体以 UTF-8 发送，否则会返回 `code=1001` + `request body must be valid JSON`。
- **HTTP 状态码不承载业务语义**：所有业务结果（包括参数错误、任务不存在、不可取消）一律返回 **HTTP 200**，结果看响应体的 `code`。两个例外：未捕获的服务内部故障返回 HTTP 500（以便告警）；访问不存在/已下线的接口返回 HTTP 404 + `code=1002`，便于区分“路由不存在”与“服务故障”。
- **鉴权**：**当前无鉴权**。工具包含 shell，等于把远程命令执行暴露在网络上，请勿直连不可信网络。

---

## 1. 响应信封

所有接口返回同一个结构：

```json
{ "code": 0, "message": "...", "data": { } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | **0 表示成功**，非 0 为各类失败。见下表 |
| `message` | string | 失败原因或补充说明；成功且无内容时该键不出现 |
| `data` | object / array | 业务数据；失败时该键不出现 |

> 值为 null 的字段一律省略（non-null 序列化），解析时不要把任何字段当作必然存在。

### 数字码表

| code | 含义 | 出现的接口 | 调用方该怎么做 |
|---|---|---|---|
| `0` | 成功 | 全部 | 读 `data` |
| `1001` | 请求不合法：字段为空/超长、taskId 格式非法、技能名不存在、model profile 不存在、JSON 解析失败 | `POST /task` | 修正请求后重发，**勿原样重试** |
| `1002` | 接口不存在（路径无对应处理器，如已下线或拼错的接口；伴随 HTTP 404） | 任意 | 检查请求路径 |
| `2001` | 任务不存在 | `GET /task/{id}`、`DELETE /task/{id}` | 检查 taskId |
| `2002` | 任务已是终态，无法取消 | `DELETE /task/{id}` | 无需处理，`message` 会给出当前状态 |
| `3001` | 队列积压超过 `agent.execution.max-queued-tasks`（默认 1000） | `POST /task` | 退避后重试 |
| `9999` | 服务内部错误（同时 HTTP 500） | 全部 | 告警 + 退避重试 |

### 关键区分：接口成功 ≠ 任务成功

任务执行失败是**业务结果**，不是接口失败。查询一个失败的任务会得到 `code=0`，失败详情在 `data` 里：

```json
{ "code": 0, "data": { "taskId": "x", "status": "failed", "error": { "code": "AGENT_TIMEOUT", "...": "..." } } }
```

判断任务是否成功，只看 `data.status`，不要看 `code`（`code` 只说明这次调用本身成没成）。

---

## 2. 任务状态机

```
        POST /task              worker 领取                执行结束
accepted ────────────────► running ────────────────► completed
    │                          │
    │                          ├── 超时/异常 ─────────► failed
    └──── DELETE /task ────────┴── DELETE ────────────► cancelled
```

| status | 含义 | 终态 |
|---|---|---|
| `accepted` | 已入队，等待 worker 领取 | 否 |
| `running` | 正在执行 | 否 |
| `completed` | 执行成功 | 是 |
| `failed` | 执行失败，`data.error` 有详情 | 是 |
| `cancelled` | 被取消 | 是 |

队列存于 MySQL（`agent_task` 表），不在内存里：进程重启后 `accepted` 任务会被重新领取，执行中被中断的任务在租约到期后也会自动重跑。

---

## 3. 提交任务

`POST /task`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `instruction` | string | 是 | 自然语言指令，≤ 32768 字符 |
| `taskId` | string | 否 | 幂等键，缺省由服务端生成。必须匹配 `[A-Za-z0-9][A-Za-z0-9._+-]{0,63}`，不得含路径分隔符（它会作为工作目录名） |
| `outputSchema` | object | 否 | JSON Schema。提供则强制返回符合该 schema 的 JSON 到 `data.result`；不提供则自由文本放 `data.resultText` |
| `skills` | string[] | 否 | 指定启用的技能名（见 `GET /skills`，大小写敏感）。不提供＝所有已安装技能可见；提供则这些技能正文被直接注入并强制生效。≤ 8 个 |
| `model` | string | 否 | 主模型 profile（`flash` / `pro`，具体端点由部署配置）。不提供＝用默认（`agent.default-model`，当前 `flash`）；填未知 profile 名返回 `code=1001`，不静默回落。 |
| `options.sync` | boolean | 否 | true＝阻塞等到终态；false（默认）＝立即返回，由调用方轮询 |
| `options.timeoutSeconds` | int | 否 | 执行预算，默认 120，上限 900。**从 worker 领取任务时开始计时，不含排队** |
| `metadata` | object | 否 | 业务上下文，服务端不解释，会**原样回显**（含嵌套结构）于所有响应。≤ 8192 字符 |

### 3.1 异步提交（推荐）

```bash
curl -X POST http://localhost:8080/api/v1/agent/task \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @task.json
```

```json
// task.json
{
  "taskId": "demo-async-1",
  "instruction": "从这句话里提取张伟的信息：张伟今年34岁，李娜今年28岁。只返回张伟。",
  "outputSchema": {
    "type": "object",
    "properties": { "name": { "type": "string" }, "age": { "type": "integer" } },
    "required": ["name", "age"]
  },
  "metadata": { "orderId": "A20260917" },
  "options": { "timeoutSeconds": 120 }
}
```

响应（HTTP 200）：

```json
{
  "code": 0,
  "data": {
    "taskId": "demo-async-1",
    "status": "accepted",
    "metadata": { "orderId": "A20260917" },
    "createdAt": "2026-09-17T14:36:59.823Z"
  }
}
```

### 3.2 提交成功怎么判断，以及幂等

**`code=0` 就表示提交成功**：这个 `taskId` 的任务已经在系统里了，`data.status` 告诉你它到哪一步。没有额外字段，结果只看 `code` 一处。

重复提交同一个 `taskId` **不会重跑任务**，而是直接返回它的当前状态，同样是 `code=0`：

```json
// 重复提交时任务还在跑 → 拿到当前进展
{ "code": 0, "data": { "taskId": "idem-1", "status": "accepted", "createdAt": "..." } }

// 重复提交时任务已完成 → 直接拿到结果，不用另外再查一次
{
  "code": 0,
  "data": {
    "taskId": "idem-1",
    "status": "completed",
    "resultText": "ok",
    "usage": { "inputTokens": 1943, "outputTokens": 2, "totalTokens": 1945 },
    "durationMs": 7829
  }
}
```

这是有意把 `taskId` 的语义定成「**确保这个任务存在，并告诉我它的状态**」：你要的结果（任务在跑、或结果已就绪）两种情况下都拿到了，所以不需要区分“这次是不是我新建的”。

实际好处：**提交请求可以无条件重试**。网络超时、响应丢失、客户端崩溃后重发，都不会造成重复执行；重发后你得到的是同一个任务的真实状态或结果。

### 3.3 同步提交

带 `"options": { "sync": true, "timeoutSeconds": 90 }`。跑完返回（HTTP 200）：

```json
{
  "code": 0,
  "data": {
    "taskId": "v2-sync-1",
    "status": "completed",
    "resultText": "ok",
    "usage": { "inputTokens": 1945, "outputTokens": 2, "totalTokens": 1947 },
    "createdAt": "2026-09-17T14:13:00.685Z",
    "startedAt": "2026-09-17T14:13:00.831Z",
    "completedAt": "2026-09-17T14:13:08.252Z",
    "durationMs": 7418
  }
}
```

**预算内没跑完，`code` 仍是 0**，`data.status` 为非终态即表示仍在执行，请改用 `GET /task/{id}` 轮询同一 id：

```json
{
  "code": 0,
  "data": {
    "taskId": "v2-sync-2",
    "status": "running",
    "createdAt": "2026-09-17T14:13:08.345Z",
    "startedAt": "2026-09-17T14:13:08.535Z"
  }
}
```

### 3.4 结构化输出

带 `outputSchema` 且成功时，`data.result` 是解析后的 JSON 对象（真实响应）：

```json
{
  "code": 0,
  "data": {
    "taskId": "dbq-sync-1",
    "status": "completed",
    "result": { "name": "张伟", "age": 34 },
    "resultText": "{\"name\":\"张伟\",\"age\":34}",
    "usage": { "inputTokens": 1929, "outputTokens": 38, "totalTokens": 1967 },
    "durationMs": 8807
  }
}
```

### 3.5 失败响应示例

```json
// 指令为空
{ "code": 1001, "message": "instruction must not be blank" }

// taskId 含路径穿越
{ "code": 1001, "message": "taskId must match [A-Za-z0-9][A-Za-z0-9._+-]{0,63} and must not contain path separators" }

// JSON 不合法 / 非 UTF-8
{ "code": 1001, "message": "request body must be valid JSON" }

// 技能名不存在（会列出可用技能）
{ "code": 1001, "message": "unknown skill(s) [nope]; available: [report-format]" }

// 队列积压超阈值
{ "code": 3001, "message": "queue depth exceeds agent.execution.max-queued-tasks (1000)" }
```

---

## 4. 查询任务

`GET /task/{taskId}`

成功（HTTP 200）：

```json
{
  "code": 0,
  "data": {
    "taskId": "v2-a1",
    "status": "completed",
    "resultText": "ok",
    "usage": { "inputTokens": 1944, "outputTokens": 2, "totalTokens": 1946 },
    "createdAt": "2026-09-17T14:10:21.189Z",
    "startedAt": "2026-09-17T14:10:22.803Z",
    "completedAt": "2026-09-17T14:10:31.698Z",
    "durationMs": 8889
  }
}
```

任务不存在（HTTP **200**，靠 code 区分，且现在带有明确原因）：

```json
{ "code": 2001, "message": "task not found: nope-xyz" }
```

`data` 字段说明：

| 字段 | 说明 |
|---|---|
| `status` | 任务状态，见 §2。**判断成功与否看它** |
| `metadata` | 提交时传的业务上下文原样回显；没传则无此键 |
| `result` | 结构化结果，仅当提交时给了 `outputSchema` 且成功 |
| `resultText` | 最终文本；有 schema 时是 JSON 字符串，无 schema 时是自由文本 |
| `error` | 仅 `status=failed` 时有值：`{code, message, retryable}` |
| `usage` | token 用量；未跑完时可能不存在 |
| `createdAt`/`startedAt`/`completedAt` | UTC ISO-8601，入库精度毫秒 |
| `durationMs` | 纯执行耗时，不含排队 |

任务执行失败的响应（`code` 仍为 0）：

```json
{
  "code": 0,
  "data": {
    "taskId": "doc-fail-1",
    "status": "failed",
    "error": {
      "code": "AGENT_TIMEOUT",
      "message": "agent exceeded budget of 1s",
      "retryable": true
    },
    "createdAt": "2026-09-17T13:12:27.246Z",
    "startedAt": "2026-09-17T13:12:27.818Z",
    "completedAt": "2026-09-17T13:12:33.076Z",
    "durationMs": 1566
  }
}
```

**轮询建议**：1–2 秒起步，指数退避到 5 秒。任务通常 5–30 秒完成，多轮工具调用可能到分钟级。

---

## 5. 取消任务

`DELETE /task/{taskId}`

取消成功返回**取消后的任务**，不用再补一次查询（HTTP 200）：

```json
{
  "code": 0,
  "data": {
    "taskId": "v2-cancel-1",
    "status": "cancelled",
    "createdAt": "2026-09-17T14:12:15.386Z",
    "startedAt": "2026-09-17T14:12:18.409Z",
    "completedAt": "2026-09-17T14:12:22.929Z"
  }
}
```

任务已终态：

```json
{ "code": 2002, "message": "task is already terminal, nothing to cancel (status=completed)" }
```

任务不存在：

```json
{ "code": 2001, "message": "task not found: nope-xyz" }
```

取消语义：

- **排队中**的任务不会再执行。
- **执行中**的任务：由本实例执行则立即中断线程；由其他实例执行只能标记取消——那一轮仍会跑完，但结果写不进去（终态写入受 `status <> 'cancelled'` 保护）。

---

## 6. 已注册工具

`GET /tools`

返回**本项目自定义的工具**（不含框架内置的 shell / 读写文件 / 列目录 / todo），反映 `agent.tools.*` 的实时开关。运维用它确认自定义能力（系统时间 + 图片/PDF 理解与抽取）是否开启。

默认开关全开时，返回 5 个自定义工具：

```json
{
  "code": 0,
  "data": [
    { "name": "extract_image_fields", "description": "从图片里结构化抽取指定字段……" },
    { "name": "extract_pdf_fields", "description": "从 PDF 文档整篇抽取指定关键信息并定位页码……" },
    { "name": "get_system_time", "description": "获取服务器当前日期和时间……" },
    { "name": "understand_image", "description": "对图片做自由理解/问答……" },
    { "name": "understand_pdf", "description": "对 PDF 做自由理解/问答……" }
  ]
}
```

说明：

- `name` 是模型调用时用的工具名，`description` 是用途说明——两者都取自**真正装配出来的工具 schema**，与运行时完全一致，不是另写的一份清单。
- 只列自定义工具：`get_system_time`、`understand_image`、`extract_image_fields`、`understand_pdf`、`extract_pdf_fields`。框架内置的 `execute_shell_command`/`view_text_file`/`write_text_file`/`insert_text_file`/`list_directory`/`todo_write` **不在此列**。
- 列表随 `agent.tools.*` 开关变化：关掉某个开关（如 `AGENT_TOOL_IMAGE_EXTRACT=false`），对应自定义工具就不再出现；四个媒体开关全关时返回空数组。

---

## 7. 技能清单

`GET /skills`

返回当前系统加载的技能名称数组。

```json
{ "code": 0, "data": ["legal-terms", "report-format"] }
```

没有安装任何技能时返回空数组（空数组不会被省略）：

```json
{ "code": 0, "data": [] }
```

说明：

- 技能放在运维目录（默认 `./agent-skills`），结构为 `<技能名>/SKILL.md`。
- **新增或修改技能不需要重启**，下一个任务即生效，本接口立刻反映变化。
- 请求里 `skills` 填的就是这里的名字，**大小写敏感**，写错返回 `code=1001`。
- 故意不放进 `/health`：技能列表随运维操作变化，与健康无关，而 health 调用频率高得多。

---

## 8. 健康检查

`GET /health`

```json
{
  "code": 0,
  "data": {
    "active": 0,
    "queued": 0,
    "maxConcurrent": 4,
    "maxQueuedTasks": 1000,
    "workerId": "JTZJ-PC-29336-be6b7e27",
    "model": "qwen",
    "permissionMode": "BYPASS"
  }
}
```

| 字段 | 说明 |
|---|---|
| `active` | 本实例正在执行的任务数 |
| `queued` | 全库排队中的任务数（跨实例汇总，读自 MySQL） |
| `maxConcurrent` | 本实例 worker 线程数，即精确并发上限 |
| `workerId` | 实例标识（主机-PID-随机串），用于定位是哪个进程领取了任务 |

没有 `status` 字段：`code=0` 且能返回就是健康。该端点不查依赖、不做探活判断。

---

## 9. 完整调用示例（提交 + 轮询）

```bash
BASE=http://localhost:8080/api/v1/agent

# 1) 提交
RESP=$(curl -s -X POST $BASE/task \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"instruction":"统计当前目录 .txt 文件数量，只回答数字","options":{"timeoutSeconds":180}}')

CODE=$(echo "$RESP" | grep -oE '"code":[0-9]+' | head -1 | cut -d: -f2)
[ "$CODE" != "0" ] && { echo "提交失败: $RESP"; exit 1; }
TASK_ID=$(echo "$RESP" | grep -oE '"taskId":"[^"]+"' | head -1 | cut -d'"' -f4)
echo "task=$TASK_ID"

# 2) 轮询到终态；注意 code 恒为 0，终态与否看 status
while :; do
  RESP=$(curl -s $BASE/task/$TASK_ID)
  STATUS=$(echo "$RESP" | grep -oE '"status":"[a-z]+"' | head -1 | sed 's/.*:"//;s/"//')
  echo "status=$STATUS"
  case "$STATUS" in
    completed)  echo "成功: $RESP"; break ;;
    failed|cancelled) echo "终止: $RESP"; break ;;
  esac
  sleep 2
done
```

## 10. 限制与默认值

| 项 | 默认 | 环境变量 |
|---|---|---|
| 指令最大长度 | 32768 字符 | `AGENT_LIMIT_INSTRUCTION` |
| outputSchema 最大 | 8192 字符 | `AGENT_LIMIT_SCHEMA` |
| metadata 最大 | 8192 字符 | `AGENT_LIMIT_METADATA` |
| 默认超时 | 120 秒 | `AGENT_DEFAULT_TIMEOUT` |
| 最大超时 | 900 秒 | `AGENT_MAX_TIMEOUT` |
| 并发上限 | 4 | `AGENT_MAX_CONCURRENT` |
| 队列积压上限（超过返 3001） | 1000 | `AGENT_MAX_QUEUED_TASKS` |
| 任务领取租约 | 90 秒 | `AGENT_LEASE_SECONDS` |
| 单任务最多领取次数 | 2 | `AGENT_MAX_ATTEMPTS` |
| 单任务最多指定技能数 | 8 | `AGENT_SKILLS_MAX_REQUESTED` |
| 注入技能正文总量上限 | 12000 字符 | `AGENT_SKILLS_MAX_INLINE_CHARS` |
| 技能开关 / 目录 | true / `./agent-skills` | `AGENT_SKILLS_ENABLED` / `AGENT_SKILLS_DIR` |

---

## 11. 使用注意

1. **判断任务成败只看 `data.status`**，`code` 只表示这次调用本身成功与否。
2. **超时不含排队**：`timeoutSeconds` 从 worker 领取任务时开始计时。排队时长看 `startedAt - createdAt`，`durationMs` 是纯执行耗时。高积压下任务总耗时可能远超 `timeoutSeconds`；`sync=true` 的等待从提交时刻算，会把排队也算进去。
3. **重试提交是安全的**：同一 `taskId` 重发不会重复执行，而是返回该任务的当前状态或已有结果，`code` 仍为 0。
4. **`error.retryable=true` 才值得重试任务**（例如 `AGENT_TIMEOUT`、`AGENT_INTERRUPTED`），用同一个 `taskId` 重发即可。
5. **进程重启不丢任务**：排队中的任务会被重新领取；执行中的任务在租约过期（默认 90 秒）后自动重跑，最多 `max-attempts` 次，超过则置 `failed` + `AGENT_LEASE_EXPIRED`。副作用：有外部写操作的任务可能被执行多次。
6. **每任务独立工作目录** `<working-dir>/<taskId>`：shell 与文件工具都被限制在该目录内，跨任务读取被拒绝。要让 agent 处理文件，先把文件放进去。
7. **无鉴权**：见文首警告。

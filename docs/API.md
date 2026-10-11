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
| `1001` | 请求不合法：字段为空/超长、taskId 格式非法、model profile 不存在、JSON 解析失败 | `POST /task` | 修正请求后重发，**勿原样重试** |
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

认领顺序不是纯 FIFO：`options.sync=true` 的任务排在普通任务前面（`sync_requested DESC, created_at ASC`），因为调用方正阻塞在 HTTP 里等结果，异步批量任务多等一轮无感。同一优先级内仍按提交先后。

老库升级需手动执行 `db/migration-005-sync-priority.sql`（新增 `sync_requested` 列），新装由 `schema.sql` 直接带上。

---

## 3. 提交任务

`POST /task`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `instruction` | string | 是 | 自然语言指令，≤ 32768 字符 |
| `taskId` | string | 否 | 幂等键，缺省由服务端生成。必须匹配 `[A-Za-z0-9][A-Za-z0-9._+-]{0,63}`，不得含路径分隔符（它会作为工作目录名） |
| `outputSchema` | object | 否 | JSON Schema。提供则强制返回符合该 schema 的 JSON 到 `data.result`；不提供则自由文本放 `data.resultText` |
| `skills` | string[] | 否 | 指定启用的技能名（见 `GET /skills`，大小写敏感）。不提供＝所有已安装技能可见；提供则这些技能正文被直接注入并强制生效。未安装的名字被忽略并记 WARN（不报错）。≤ 8 个 |
| `model` | string | 否 | 主模型 profile（`flash` / `pro`，具体端点由部署配置）。不提供＝用默认（`agent.default-model`，当前 `flash`）；填未知 profile 名返回 `code=1001`，不静默回落。 |
| `options.sync` | boolean | 否 | true＝阻塞等到终态；false（默认）＝立即返回，由调用方轮询 |
| `options.timeoutSeconds` | int | 否 | 执行预算，默认 120，上限 900。**从 worker 领取任务时开始计时，不含排队** |
| `metadata` | object | 否 | 业务上下文，服务端不解释，会**原样回显**（含嵌套结构）于所有响应。≤ 8192 字符 |
| `callbackUrl` | string | 否 | 任务终态后服务端 POST JSON 到该地址。必须以 `http://` 或 `https://` 开头，≤ 1024 字符。投递结果记录在数据库 `callback_status` 列（见 §3A） |

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

// 队列积压超阈值
{ "code": 3001, "message": "queue depth exceeds agent.execution.max-queued-tasks (1000)" }
```

---

## 3A. 回调通知

任务到达终态（`completed` / `failed` / `cancelled`）后，服务端向 `callbackUrl` 发一次 HTTP POST。

### 请求特征

| 项 | 值 |
|---|---|
| 方法 | POST |
| Content-Type | `application/json; charset=utf-8` |
| 超时 | 10 秒（连接+读取） |
| 重定向 | 不跟随 |
| 重试 | 无（失败不自动重试，由补偿机制或手动触发） |

### 回调 Body

**与 `GET /task/{taskId}` 响应结构完全一致**（同样的信封 + 同样的 `data`）：

```json
{
  "code": 0,
  "data": {
    "taskId": "6a86f6d7...",
    "status": "completed",
    "resultText": "1 + 1 = 2",
    "result": {"name":"张伟","age":34},
    "metadata": {"orderId":"A20260917"},
    "usage": {"inputTokens":4759,"outputTokens":9,"totalTokens":4768},
    "createdAt": "2026-09-23T15:20:56.644Z",
    "startedAt": "2026-09-23T15:20:57.799Z",
    "completedAt": "2026-09-23T15:21:08.471Z",
    "durationMs": 10662
  }
}
```

失败时：
```json
{
  "code": 0,
  "data": {
    "taskId": "abc123",
    "status": "failed",
    "error": {"code":"AGENT_TIMEOUT","message":"exceeded 180s budget","retryable":true},
    "metadata": {"orderId":"A20260917"},
    "createdAt": "2026-09-23T15:20:56.644Z",
    "startedAt": "2026-09-23T15:20:57.799Z",
    "completedAt": "2026-09-23T15:23:57.800Z",
    "durationMs": 180001
  }
}
```

接收方用同一套解析逻辑即可处理回调和查询结果。

### 接收方要求

- 返回 **HTTP 2xx** 表示接收成功，服务端记录 `callback_status='success'`
- 非 2xx / 超时 / 连接失败 → `callback_status='failed'`

### 手动重推

数据库 `agent_task` 表的 `callback_status` 列控制投递状态：

| callback_status | 含义 |
|---|---|
| `NULL` | 未配置回调 |
| `success` | 已投递成功 |
| `failed` | 投递失败 |
| `pending` | 等待投递（系统每 30 秒扫描一次） |
| `delivering` | 投递中（内部抢占状态） |

**重推操作**：把状态改回 `pending`，系统自动补偿投递：

```sql
-- 重推单个任务
UPDATE agent_task SET callback_status='pending' WHERE task_id='xxx';

-- 批量重推所有失败的
UPDATE agent_task SET callback_status='pending'
  WHERE callback_status='failed' AND callback_url IS NOT NULL;
```

### 多实例安全

多个服务实例同时补偿时，通过 `UPDATE ... WHERE callback_status='pending'` 条件更新抢占，只有一个实例能拿到 `affected=1` 并执行投递，不会重复推送。

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

返回**本项目自定义的工具**（不含框架内置的 shell / 读写文件 / 列目录 / todo），反映 `agent.tools.*` 的实时开关。运维用它确认自定义能力（系统时间 + 文档理解与抽取 + 印章/发票识别）是否开启。

默认开关全开时，返回 5 个自定义工具：

```json
{
  "code": 0,
  "data": [
    { "name": "extract_document_fields", "description": "文档结构化字段抽取（图片/PDF/TXT）……" },
    { "name": "get_system_time", "description": "获取服务器当前日期和时间……" },
    { "name": "recognize_invoice", "description": "发票识别（专用接口，返回结构化发票字段）……" },
    { "name": "recognize_seal", "description": "印章识别（专用接口，返回置信度/类型/公司名）……" },
    { "name": "understand_document", "description": "文档自由理解/问答（图片/PDF/TXT）……" }
  ]
}
```

说明：

- `name` 是模型调用时用的工具名，`description` 是用途说明——两者都取自**真正装配出来的工具 schema**，与运行时完全一致，不是另写的一份清单。
- 只列自定义工具：`get_system_time`、`understand_document`、`extract_document_fields`、`recognize_seal`、`recognize_invoice`。框架内置的 `execute_shell_command`/`view_text_file`/`write_text_file`/`insert_text_file`/`list_directory`/`todo_write` **不在此列**。
- 列表随 `agent.tools.*` 开关变化：关掉某个开关（如 `AGENT_TOOL_DOCUMENT_EXTRACT=false`、`AGENT_TOOL_SEAL_RECOGNITION=false`），对应自定义工具就不再出现；全部关掉时返回空数组。

### 6.1 `extract_document_fields` 的返回口径

工具结果是一个 JSON 字符串，形状固定：

```json
{"status":"complete","total_units":2,
 "results":[{"field":"签订日期","value":"2026年3月18日","units":[1]},
            {"field":"甲方","value":"中交二航局第一工程有限公司","units":[1],
             "alternates":[{"value":"中交二航局第一工程有限公司（盖章）","units":[2]}]}],
 "failed_units":[]}
```

- **每个请求的字段都恰好出现一条**；文档里确实没有时 `value` 是 JSON `null`、`units` 是空数组。这是服务端写死的口径，不是模型措辞——下游按 `value === null` 判缺失，不要匹配「未提及」这类文案。
- 有 `value=null` 且没有失败单元时，返回值额外带 `null_note`：全文已遍历、已按语义匹配过别名，**null 就是终局结论**。服务端同时拦住重复抽取——同一来源在一次任务里最多抽 3 次，超过直接拒绝、不重跑（实测模型会为一个 null 字段换 8 个字段名把同一来源抽 14 遍，2 页扫描件白跑 188s）。抽取类任务调一次即结束，**不得**用 `understand_document` 补 null 字段。
- `units` 含义：单个来源时＝文本块序号（按字符切块，不是页码）；多个来源时＝来源序号（按 `source` 里的顺序，从 1 起）。
- 同一字段抽到多个不同值时**不会**摊成多条互斥结果：`value` 是权威值（非占位符优先 → 出现次数多优先 → 首次出现更早优先），其余候选放进 `alternates`，各带自己的 `units`。这样下游不必再猜哪条算数、也不必换工具自查。
- 抽取器自己补出来的、未被请求的字段进 `extra`，不与 `results` 混在一起。
- `status`：`complete`＝所有单元都成功；`partial`＝有单元失败，失败单元在 `failed_units`。**OCR 缺页也算 partial**（此时另有 `partial_note` 说明缺了哪些来源，且不再带 `null_note`）。
- 抽取管线只有一条：图片/PDF 先 OCR 成文本（PDF 逐页识别、按页追加到 `.ocr/` 下的同名 `.txt`，每页前有 `--- 第 N 页 ---`），再按字符切块抽取。没有页型探测，也没有视觉/OCR 双通道回退。
- OCR 原文只出现在任务目录的 `.ocr/` 子目录里：工具返回值不含该路径，全文也不常驻内存（按块读）。它在**整次任务内保留**，任务跑完由服务端删掉整个 `.ocr/`，原文不留盘。
- 一次任务内同一份内容只 OCR 一次：txt 文件名带内容的 SHA-256 前缀，文件已存在就直接复用（**仅限完整产物**，见下一条）（模型先抽 5 个字段再补抽 1 个时不会把 72 页重新识别一遍）。按来源字符串去重做不到这点——模型自己 `curl -o` 出来的本地副本和原 URL 是两个 key。**没有内存缓存**：工具实例不保存全文或结果副本，几十 MB 的扫描件文本不压在堆上。
- OCR 有页失败时不算「全文已遍历」：txt 里留一行 `--- 第 N 页 OCR 失败（该页内容缺失）---`，
  产物改名带 `-partial`（不参与复用，下次调用重跑），返回值改为 `status=partial` + `partial_note`、
  去掉 `null_note`。一页瞬时故障不能被宣告成「文档里没有这个字段」。
- 所有文档抓取（OCR 转文本、`recognize_seal`、`recognize_invoice`、PDF 理解）都过同一个
  `agent.pdfs.ssrf-allowlist` 主机白名单（后缀匹配，留空=不限制）。
- **工具幂等**：`extract_document_fields` / `understand_document` / `recognize_seal` / `recognize_invoice` 每次调用先查
  任务目录里的 `.ocr/calls.json`——同一工具 + 同一组参数（来源列表、字段列表都排序归一，「A B」与「B A」同一个 key）
  第二次调用**直接返回第一次的返回**，不重 OCR、不重跑模型、不再打接口。台账只存工具自己的结构化结论
  （超过 32000 字符就不存）。**计数与存结果是两件事**：以 `Error:` 开头的返回、以及 seal/invoice 里所有来源都失败的
  返回（`status=error`）不存结果（接口抖动可以重试），但仍然计数，否则模型对着一个一直失败的来源会无限重试；
  OCR 全文、图片字节都不进台账也不进内存，
  任务目录删掉台账就没了，跨任务也不复用——它是重复调用闸，不是内容缓存。
  另外三个工具（seal / invoice / understand）还有硬闸：一次任务最多**真跑** 4 次，超了直接返回拒绝文案；
  `extract_document_fields` 用的是上面那条「同一来源最多抽 3 次」的闸。`get_system_time` 不参与幂等。

- 藏进子目录本身拦不住模型（`list_directory` 不过滤点开头目录，`view_text_file` 也不校验后缀），所以隔离靠不注册读文件的工具，见下一条。
- 想彻底断掉「模型换工具自查」，就把内置文件工具关掉：`AGENT_TOOL_READ=false`（同时去掉 `view_text_file` 和 `list_directory`）、必要时 `AGENT_TOOL_SHELL=false`。框架的权限规则只能按工具名 deny，做不到按路径，所以路径级隔离只能靠不注册工具。
- **shell 建议直接关（`AGENT_TOOL_SHELL=false`）**：框架的 shell 工具不截断输出，模型 `curl` 一个大文件到 stdout 会把整个文件灌进主模型上下文（实测 30MB PDF ≈ 13.7 万字符二进制），任务随即空转到 max-iters 无结果；`AGENT_SHELL_ALLOWED=curl` 只约束可执行文件名，管不住输出体积，且 curl 落盘的本地副本还能绕过 `AGENT_PDF_SSRF_ALLOWLIST`（本地路径不再走 URL 白名单校验）。文档工具都直接吃 URL，关掉 shell 不影响任何正常能力。
- OCR 文本通道**只用于字段抽取**。`understand_document` 对 PDF 始终走视觉，因为 OCR 文本会丢掉版面与图形信息。
- `understand_document` **不暴露页范围**：PDF 按 `min(agent.pdfs.pages-per-call, agent.vision.max-images)` 页一批，服务端遍历全部页、每批问同一个问题，再用 `agent.text-model` 把各批归并成**一段**回答（批与批并发，`agent.pdfs.understand-parallelism` 默认 2，归并仍严格按批序）；图片来源超过 `max-images` 同样自动分批。TXT 超过一个文本块也一样分批归并。失败批次会在回答末尾列出，不会静默丢页——早先"超过单次上限就返回一段文案让模型自己用 `page_range` 分批"的做法已删掉，它把遍历全文的责任推给了模型，实测模型会为一个问题连开 5 次不同页范围。
- **文档类指令必须真的读文件**：指令里点名了文档文件（`http(s)` URL 带 pdf/jpg/png/txt/… 后缀）、但整趟运行一个工具都没调时，服务端会带「必须先调工具」的硬约束重试一次；仍不调工具就 `status=failed` + `error.code=NO_RESULT`，绝不把模型凭记忆编出来的字段值当结果返回。要退回「只告警不拦截」，设 `AGENT_FORCE_DOCUMENT_TOOL=false`。

### 6.2 `recognize_seal` / `recognize_invoice` 的返回口径

这两个是**专用识别接口**的内置工具（综合服务平台 `/api/v3/seal/preprocess`、`/api/v3/invoice/preprocess`），取代早先技能里的 curl 示例。

共同点：

- `source` 直接吃 `http(s)` URL 或任务目录内的路径，可多个（空格/换行分隔）；**下载在工具内部完成**，模型不需要也不应该先 `curl -o` 落盘。
- 返回 `{status, results:[{source, ...}]}`，`status` = `ok` / `partial` / `error`；单个来源失败只影响它自己，原因在该来源的 `error` 里。
- 返回值只含结构化结论，**不含图片 base64**（印章裁剪图固定不取）。内存里没有缓存，幂等靠任务目录里的调用台账（见 6.1）：同一组参数第二次调用直接返回上次结果，所以每个来源调一次就够。
- 服务地址：`agent.ips.base-url`——OCR / 印章 / 发票是同一台服务的三个接口，一个基址管三个。

`recognize_seal` → `results[].seals[] = {confidence, seal_type, company}`（`seal_type` 1=圆形红章、2=圆形灰章；`company` 即接口 `ocr_result`），另有 `seal_count`。`seals=[]` 表示未检出印章，是权威结论而非错误。

`recognize_invoice` → `results[] = {confidence, fields}`。接口的 `ocr_text` 本来是个 JSON 字符串，工具已解成 `fields` 对象（key 就是 `发票号码`/`购货方公司名称`/`发票总金额` 这些接口口径名，不要改名）；解不开才退回 `fields_raw`。检测不到有效发票时该来源返回 `error`（附接口给的纯文本原因）。

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
2. **超时不含排队**：`timeoutSeconds` 从 worker 领取任务时开始计时。排队时长看 `startedAt - createdAt`，`durationMs` 是纯执行耗时。高积压下任务总耗时可能远超 `timeoutSeconds`；`sync=true` 的等待从提交时刻算，会把排队也算进去。因此 `sync=true` 的任务在队列里会被**优先认领**，不会被前面的大批量任务压住；但它前面若已有任务在跑，仍要等该任务结束（单 worker 时尤其明显）。
3. **重试提交是安全的**：同一 `taskId` 重发不会重复执行，而是返回该任务的当前状态或已有结果，`code` 仍为 0。
4. **`error.retryable=true` 才值得重试任务**（例如 `AGENT_TIMEOUT`、`AGENT_INTERRUPTED`），用同一个 `taskId` 重发即可。
5. **进程重启不丢任务**：排队中的任务会被重新领取；执行中的任务在租约过期（默认 90 秒）后自动重跑，最多 `max-attempts` 次，超过则置 `failed` + `AGENT_LEASE_EXPIRED`。副作用：有外部写操作的任务可能被执行多次。
6. **每任务独立工作目录** `<working-dir>/<taskId>`：shell 与文件工具都被限制在该目录内，跨任务读取被拒绝。要让 agent 处理文件，先把文件放进去。
   `AGENT_DELETE_WORKSPACE_ON_FINISH=true` 时该目录在任务终态后整个删除（磁盘不再累积扫描件产物，代价是事后没法进目录查产物做归因）；默认 `false` 保留目录做审计，只删里面的 OCR 中间文本 `<taskDir>/.ocr`——原文不允许留盘，也不允许跨任务被读到。
7. **无鉴权**：见文首警告。

-- Agent task store.
-- Applied automatically by the mysql container on first boot (mounted into
-- /docker-entrypoint-initdb.d) and also by Spring SQL init as a fallback.

CREATE TABLE IF NOT EXISTS agent_task (
    task_id        VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL COMMENT 'accepted|running|completed|failed|cancelled',

    -- request snapshot
    instruction    MEDIUMTEXT   NOT NULL,
    output_schema  LONGTEXT     NULL     COMMENT 'caller-provided JSON Schema, stored as text',
    business_meta  LONGTEXT     NULL     COMMENT 'caller metadata, opaque passthrough',
    timeout_seconds INT         NOT NULL DEFAULT 120 COMMENT 'clamped per-task budget; must survive a restart',
    skill_names      VARCHAR(512) NULL   COMMENT 'comma-separated selected skill names; NULL = all installed skills',
    model_name       VARCHAR(64)  NULL   COMMENT 'main-model profile for this task (flash|pro); NULL = default at run time',
    sync_requested   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '调用方同步等待结果：认领时优先于普通任务',
    callback_url     VARCHAR(1024) NULL  COMMENT '任务完成后通知地址；NULL=不通知',
    callback_status  VARCHAR(16)  NULL   COMMENT '回调投递结果：NULL=未配置, success, failed',
    callback_at      DATETIME(3)  NULL   COMMENT '最后一次回调时间',

    -- result
    result_json    LONGTEXT     NULL     COMMENT 'structured result when output_schema present',
    result_text    MEDIUMTEXT   NULL     COMMENT 'final assistant text (always best-effort)',

    -- failure
    error_code     VARCHAR(64)  NULL,
    error_message  TEXT         NULL,
    retryable      TINYINT(1)   NOT NULL DEFAULT 0,

    -- usage
    input_tokens   BIGINT       NULL,
    output_tokens  BIGINT       NULL,
    total_tokens   BIGINT       NULL,
    duration_ms    BIGINT       NULL,

    -- queue machinery: the table is also the work queue (no in-memory queue, no Redis)
    worker_id        VARCHAR(64)  NULL     COMMENT 'which process claimed this task',
    lease_expires_at DATETIME(3)  NULL     COMMENT 'claim heartbeat deadline; expired = worker died',
    attempt          INT          NOT NULL DEFAULT 0 COMMENT 'how many times a worker has claimed it',

    created_at     DATETIME(3)  NOT NULL,
    started_at     DATETIME(3)  NULL,
    completed_at   DATETIME(3)  NULL,
    updated_at     DATETIME(3)  NOT NULL,

    PRIMARY KEY (task_id),
    KEY idx_status_created (status, created_at),
    KEY idx_lease (status, lease_expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- 工具调用链：一条调用一行，agent_task 的一对多子表（migration-007 同款）。
-- 写它在终态写入之后、包在 try/catch 里：观测数据不值得让任务失败，表缺失也不影响任务。
CREATE TABLE IF NOT EXISTS agent_tool_call (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    task_id      VARCHAR(64)  NOT NULL COMMENT 'agent_task.task_id：一对多的「一」',
    attempt      INT          NOT NULL DEFAULT 0 COMMENT '第几次认领这趟运行（agent_task.attempt）；重试过的任务每一趟各留一条链',
    seq          INT          NOT NULL COMMENT '本次运行内的第几次调用，从 1 开始',
    tool         VARCHAR(64)  NOT NULL COMMENT '工具名；模型跑镜像内置 ips CLI 时记的是 shell 工具本身',
    args         TEXT         NULL     COMMENT '调用参数 JSON，服务端按 agent.trace.max-args-chars 截断；NULL=采集关闭',
    at_ms        BIGINT       NOT NULL COMMENT '距本次运行开始的毫秒数',
    duration_ms  BIGINT       NULL     COMMENT '该次调用耗时；NULL=没等到结果就中断',
    state        VARCHAR(16)  NOT NULL COMMENT 'success|error|denied|interrupted|running',
    created_at   DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_task_attempt_seq (task_id, attempt, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = 'agent 工具调用链：一条调用一行，agent_task 的一对多子表。纯观测，不参与任务状态机';

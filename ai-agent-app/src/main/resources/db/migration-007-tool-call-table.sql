-- Migration 007: 工具调用链改成独立子表 agent_tool_call，一条调用一行。
--
-- 为什么不用一列 JSON：调用链是「一趟任务 : 多次调用」的一对多。JSON 列读要解析、查不动
-- （「这周哪些任务调过 ips seal」只能 LIKE），终态那条 UPDATE 还要顺带搬一整坨文本。
-- 一行一次调用之后，按工具查、按天统计、只看某一次重试，都是普通 SQL。
--
-- 顺序有讲究：先建表，最后一句才删老列。客户端遇错即停，建好表就是最重要的结果。
--
-- 重复执行本文件：CREATE TABLE IF NOT EXISTS 幂等；最后那句 DROP 在没这一列的库上报
-- "Can't DROP 'tool_trace'"，忽略即可——只有当年打过这版的测试库有这一列，生产从来没有。

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

-- 老列收尾：当年加过 tool_trace 那一列的库才需要（代码已不再读写它，留着只占 agent_task 宽度）。
ALTER TABLE agent_task DROP COLUMN tool_trace;

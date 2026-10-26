-- Migration 006: 工具调用链。
--
-- 任务跑完过去只剩 resultText 和 token 数：排查「这条营业执照为什么漏字段」时，看不出模型到底
-- 调了哪些工具、调了几次、哪一次失败。加一列把调用顺序、参数、耗时、成败记下来，任务详情直接可见。
-- 记的是调用事实，不含返回值；参数与条数在服务端截断（agent.trace.*）。
--
-- 重复执行会报 "Duplicate column name"，忽略即可。先查：
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_task' AND COLUMN_NAME = 'tool_trace';

ALTER TABLE agent_task
    ADD COLUMN tool_trace MEDIUMTEXT NULL
        COMMENT 'agent 工具调用链 JSON：count/truncated/tools/calls[]' AFTER duration_ms;

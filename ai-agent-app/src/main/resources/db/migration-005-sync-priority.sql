-- Migration 005: 同步任务优先认领。
--
-- sync=true 的调用方是阻塞在 HTTP 请求里等结果的；旧口径下它和后台批量任务一样按 created_at
-- 排队，会排在一个 72 页扫描件后面白等几分钟。加一列把"调用方在等"这件事持久化下来，
-- 认领时先排它。老库升级用本文件；新装直接从 schema.sql 拿到该列。
--
-- 重复执行会报 "Duplicate column name"，忽略即可。先查：
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_task' AND COLUMN_NAME = 'sync_requested';

ALTER TABLE agent_task
    ADD COLUMN sync_requested TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '调用方同步等待结果：认领时优先于普通任务' AFTER model_name;

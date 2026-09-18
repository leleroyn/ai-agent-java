-- Migration 001: turn agent_task into the work queue (MySQL 5.7 compatible).
--
-- Applies to databases created before the DB-backed queue. New installs get these columns
-- directly from schema.sql, so this file is for existing deployments only.
--
-- Idempotent guard: re-running the ALTER below fails with "Duplicate column name", which is
-- safe to ignore. To check first:
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_task'
--      AND COLUMN_NAME IN ('worker_id','lease_expires_at','attempt','timeout_seconds');

ALTER TABLE agent_task
    ADD COLUMN worker_id        VARCHAR(64)  NULL COMMENT 'which process claimed this task',
    ADD COLUMN lease_expires_at DATETIME(3)  NULL COMMENT 'claim heartbeat deadline; expired = worker died',
    ADD COLUMN attempt          INT          NOT NULL DEFAULT 0 COMMENT 'how many times a worker has claimed it',
    ADD COLUMN timeout_seconds  INT          NOT NULL DEFAULT 120 COMMENT 'clamped per-task budget; must survive a restart';

ALTER TABLE agent_task ADD INDEX idx_lease (status, lease_expires_at);

-- Rows left active by the previous process are recovered automatically at startup by the
-- lease reaper: they return to 'accepted' and are re-claimed. No manual UPDATE is required.

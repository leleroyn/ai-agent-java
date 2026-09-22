-- Migration 003: per-task main-model selection (MySQL 5.7 compatible).
--
-- Applies to databases created before the model-profile (flash / pro) feature. New installs get
-- the column from schema.sql, so this file is for existing deployments only.
--
-- Re-running is safe to ignore: it fails with "Duplicate column name 'model_name'".

ALTER TABLE agent_task
    ADD COLUMN model_name VARCHAR(64) NULL COMMENT 'main-model profile for this task (flash|pro); NULL = default at run time';

-- NULL means "use the configured default model", which matches the pre-feature behaviour (a
-- single global model), so no backfill is required for existing rows.

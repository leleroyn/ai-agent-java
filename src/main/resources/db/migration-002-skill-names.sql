-- Migration 002: per-task skill selection (MySQL 5.7 compatible).
--
-- Applies to databases created before the skill feature. New installs get the column from
-- schema.sql, so this file is for existing deployments only.
--
-- Re-running is safe to ignore: it fails with "Duplicate column name 'skill_names'".

ALTER TABLE agent_task
    ADD COLUMN skill_names VARCHAR(512) NULL COMMENT 'comma-separated selected skill names; NULL = all installed skills';

-- NULL means "expose every installed skill", which is exactly the pre-skill behaviour, so no
-- backfill is required for existing rows.

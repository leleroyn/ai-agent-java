-- 工具调用链的保留期清理（agent.trace.retention-days，默认 30 天）按 created_at 删行。
-- agent_tool_call 建表时只给了 (task_id, attempt, seq) 唯一键，没有 created_at 的索引，
-- 每天那条 DELETE 会全表扫。这一条把索引补上：InnoDB ADD KEY 走 INPLACE，不锁写。
--
-- 只在新库上执行（migration-007 已建表）。MySQL 5.7 的 ADD KEY 没有 IF NOT EXISTS，
-- 重复执行报 Duplicate key name，先 SHOW INDEX FROM agent_tool_call; 确认。
-- 不执行也不影响功能：清理照常跑，只是每次全表扫一遍。

ALTER TABLE agent_tool_call ADD KEY idx_created_at (created_at);

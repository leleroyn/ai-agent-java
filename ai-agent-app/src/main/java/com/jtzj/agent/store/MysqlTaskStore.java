package com.jtzj.agent.store;

import com.jtzj.agent.core.model.ToolCallRecord;
import com.jtzj.agent.core.model.TaskRecord;
import com.jtzj.agent.core.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** MySQL-backed store using plain JDBC; status transitions are guarded by WHERE clauses. */
@Repository
public class MysqlTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlTaskStore.class);

    /**
     * MySQL deadlocks are routine while several workers update the same hot rows, so every
     * write on the queue path is retried. {@code DEADLOCK FOUND} is always safe to retry here
     * because each statement is a single conditional update.
     */
    private static final int LOCK_RETRIES = 4;

    /** Reaper batch size — keeps one sweep from holding many row locks at once. */
    private static final int REAP_BATCH = 200;

    private static final String COLUMNS = """
            task_id, status, instruction, output_schema, business_meta, timeout_seconds, skill_names, model_name,
            sync_requested, callback_url, callback_status, callback_at,
            result_json, result_text, error_code, error_message, retryable,
            input_tokens, output_tokens, total_tokens, duration_ms, attempt,
            created_at, started_at, completed_at, updated_at
            """;

    /** 保留期清理一次删多少行：5.7 没有 chunk 概念，一次删太多会把锁握太久。 */
    private static final int PURGE_BATCH = 5000;

    private final JdbcTemplate jdbc;

    public MysqlTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskRecord> MAPPER = (ResultSet rs, int rowNum) -> {
        TaskRecord r = new TaskRecord();
        r.setTaskId(rs.getString("task_id"));
        r.setStatus(TaskStatus.fromWire(rs.getString("status")));
        r.setInstruction(rs.getString("instruction"));
        r.setOutputSchema(rs.getString("output_schema"));
        r.setMetadata(rs.getString("business_meta"));
        r.setTimeoutSeconds(readInt(rs, "timeout_seconds"));
        r.setAttempt(readInt(rs, "attempt"));
        r.setSkillNames(rs.getString("skill_names"));
        r.setModelName(rs.getString("model_name"));
        boolean syncRequested = rs.getBoolean("sync_requested");
        r.setSyncRequested(rs.wasNull() ? null : syncRequested);
        r.setCallbackUrl(rs.getString("callback_url"));
        r.setCallbackStatus(rs.getString("callback_status"));
        Timestamp cbAt = rs.getTimestamp("callback_at");
        r.setCallbackAt(cbAt == null ? null : cbAt.toInstant());
        r.setResultJson(rs.getString("result_json"));
        r.setResultText(rs.getString("result_text"));
        r.setErrorCode(rs.getString("error_code"));
        r.setErrorMessage(rs.getString("error_message"));
        boolean retryable = rs.getBoolean("retryable");
        r.setRetryable(rs.wasNull() ? null : retryable);
        r.setInputTokens(readLong(rs, "input_tokens"));
        r.setOutputTokens(readLong(rs, "output_tokens"));
        r.setTotalTokens(readLong(rs, "total_tokens"));
        r.setDurationMs(readLong(rs, "duration_ms"));
        r.setCreatedAt(readInstant(rs, "created_at"));
        r.setStartedAt(readInstant(rs, "started_at"));
        r.setCompletedAt(readInstant(rs, "completed_at"));
        r.setUpdatedAt(readInstant(rs, "updated_at"));
        return r;
    };

    private static Long readLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer readInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    @Override
    public boolean insertIfAbsent(TaskRecord r) {
        String sql = """
                INSERT IGNORE INTO agent_task (
                    task_id, status, instruction, output_schema, business_meta, timeout_seconds,
                    skill_names, model_name, sync_requested, callback_url, retryable, attempt, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
                """;
        Instant now = r.getCreatedAt() == null ? Instant.now() : r.getCreatedAt();
        try {
            int affected = jdbc.update(sql,
                    r.getTaskId(),
                    r.getStatus().wire(),
                    r.getInstruction(),
                    r.getOutputSchema(),
                    r.getMetadata(),
                    r.getTimeoutSeconds(),
                    r.getSkillNames(),
                    r.getModelName(),
                    r.getSyncRequested() != null && r.getSyncRequested() ? 1 : 0,
                    r.getCallbackUrl(),
                    ts(now),
                    ts(now));
            return affected == 1;
        } catch (DuplicateKeyException e) {
            // INSERT IGNORE normally absorbs this; kept for drivers that still raise it.
            return false;
        }
    }

    /**
     * Take one queued asynchronous task.
     *
     * <p>Candidates are read first, then each is claimed with a guarded update. Two workers
     * that pick the same candidate serialise on the row lock and only one gets
     * {@code affected == 1}; the other falls through to the next candidate.
     *
     * <p>Sync rows are filtered out here: they are claimed only through {@link #claimNextSync},
     * which the poller runs on its single lane worker.
     */
    @Override
    public Optional<TaskRecord> claimNext(String workerId, Duration lease, int peekLimit,
                                          boolean mayClaimSync) {
        // 同步通道开着时，异步候选里根本不出现同步行；关掉时才退回这里，并且优先认领。
        String sql = "SELECT task_id FROM agent_task WHERE status = 'accepted'"
                + (mayClaimSync ? " ORDER BY sync_requested DESC," : " AND sync_requested = 0 ORDER BY")
                + " created_at ASC, task_id ASC LIMIT ?";
        return claimCandidates(sql, workerId, lease, peekLimit);
    }

    /** Take one queued sync task; identical claim mechanics to {@link #claimNext}. */
    @Override
    public Optional<TaskRecord> claimNextSync(String workerId, Duration lease, int peekLimit) {
        return claimCandidates("SELECT task_id FROM agent_task"
                + " WHERE status = 'accepted' AND sync_requested = 1"
                + " ORDER BY created_at ASC, task_id ASC LIMIT ?", workerId, lease, peekLimit);
    }

    private Optional<TaskRecord> claimCandidates(String candidateSql, String workerId,
                                                 Duration lease, int peekLimit) {
        List<String> candidates = jdbc.queryForList(candidateSql, String.class,
                Math.max(1, peekLimit));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        for (String taskId : candidates) {
            int affected = retryOnContention("claim " + taskId,
                    () -> jdbc.update("""
                            UPDATE agent_task
                               SET status = 'running', started_at = ?, worker_id = ?,
                                   lease_expires_at = ?, attempt = attempt + 1, updated_at = ?
                             WHERE task_id = ? AND status = 'accepted'
                            """, ts(now), workerId, ts(now.plus(lease)), ts(now), taskId));
            if (affected == 1) {
                return find(taskId);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean renewLease(String taskId, String workerId, Duration lease, Instant now) {
        return retryOnContention("renewLease " + taskId,
                () -> jdbc.update("""
                        UPDATE agent_task
                           SET lease_expires_at = ?, updated_at = ?
                         WHERE task_id = ? AND worker_id = ? AND status = 'running'
                        """, ts(now.plus(lease)), ts(now), taskId, workerId) == 1);
    }

    /**
     * Re-queue abandoned tasks one row at a time.
     *
     * <p>A single {@code UPDATE ... WHERE status='running' AND lease_expires_at < ?} would take
     * next-key locks across {@code idx_lease}, and workers renew leases by writing that exact
     * column — measured as a real deadlock that destroyed a finished task's result. Selecting
     * ids first and updating by primary key removes the range lock entirely.
     */
    @Override
    public int requeueExpiredLeases(int maxAttempts, Instant now) {
        List<String> ids = jdbc.queryForList(
                "SELECT task_id FROM agent_task"
                        + " WHERE status = 'running' AND lease_expires_at < ? AND attempt < ?"
                        + " ORDER BY lease_expires_at ASC LIMIT " + REAP_BATCH,
                String.class, ts(now), maxAttempts);
        int requeued = 0;
        for (String taskId : ids) {
            requeued += retryOnContention("requeue " + taskId,
                    () -> jdbc.update("""
                            UPDATE agent_task
                               SET status = 'accepted', worker_id = NULL, lease_expires_at = NULL,
                                   updated_at = ?
                             WHERE task_id = ? AND status = 'running' AND lease_expires_at < ?
                            """, ts(now), taskId, ts(now)));
        }
        return requeued;
    }

    @Override
    public int failExhaustedLeases(int maxAttempts, String message, Instant now) {
        List<String> ids = jdbc.queryForList(
                "SELECT task_id FROM agent_task"
                        + " WHERE status = 'running' AND lease_expires_at < ? AND attempt >= ?"
                        + " ORDER BY lease_expires_at ASC LIMIT " + REAP_BATCH,
                String.class, ts(now), maxAttempts);
        int failed = 0;
        for (String taskId : ids) {
            failed += retryOnContention("fail-exhausted " + taskId,
                    () -> jdbc.update("""
                            UPDATE agent_task
                               SET status = 'failed', error_code = 'AGENT_LEASE_EXPIRED',
                                   error_message = ?, retryable = 0, worker_id = NULL,
                                   lease_expires_at = NULL, completed_at = ?, updated_at = ?
                             WHERE task_id = ? AND status = 'running' AND lease_expires_at < ?
                            """, truncate(message, 4000), ts(now), ts(now), taskId, ts(now)));
        }
        return failed;
    }

    /**
     * Retry a write that lost an InnoDB deadlock or hit a lock timeout.
     *
     * @param what  label for the warning log
     * @param action the single conditional statement to (re)run
     */
    private <T> T retryOnContention(String what, Supplier<T> action) {
        int losses = 0;
        while (true) {
            try {
                return action.get();
            } catch (ConcurrencyFailureException e) {
                losses++;
                if (losses >= LOCK_RETRIES) {
                    log.error("{} still losing locks after {} attempts", what, losses, e);
                    throw e;
                }
                log.warn("{} lost a lock ({}); retry {}/{}",
                        what, e.getClass().getSimpleName(), losses, LOCK_RETRIES - 1);
                try {
                    Thread.sleep(50L * losses);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @Override
    public long countQueuedSync() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task WHERE status = 'accepted' AND sync_requested = 1",
                Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public long countByStatus(TaskStatus status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task WHERE status = ?", Long.class, status.wire());
        return count == null ? 0L : count;
    }

    @Override
    public Optional<TaskRecord> find(String taskId) {
        String sql = "SELECT " + COLUMNS + " FROM agent_task WHERE task_id = ?";
        List<TaskRecord> rows = jdbc.query(sql, MAPPER, taskId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Legacy direct transition, kept for callers that already hold a claim. The queue path is
     * {@link #claimNext}; this only succeeds while the row is still {@code accepted}.
     */
    @Override
    public boolean markRunning(String taskId, Instant startedAt) {
        String sql = """
                UPDATE agent_task
                   SET status = 'running', started_at = ?, updated_at = ?
                 WHERE task_id = ? AND status = 'accepted'
                """;
        return jdbc.update(sql, ts(startedAt), ts(startedAt), taskId) == 1;
    }

    @Override
    public boolean markCompleted(String taskId,
                                 String workerId,
                                 String resultJson,
                                 String resultText,
                                 Long inputTokens,
                                 Long outputTokens,
                                 Long totalTokens,
                                 Instant completedAt,
                                 long durationMs) {
        String sql = """
                UPDATE agent_task
                   SET status = 'completed', result_json = ?, result_text = ?,
                       input_tokens = ?, output_tokens = ?, total_tokens = ?,
                       duration_ms = ?, completed_at = ?, updated_at = ?
                 WHERE task_id = ? AND worker_id = ? AND status <> 'cancelled'
                """;
        return retryOnContention("markCompleted " + taskId,
                () -> jdbc.update(sql,
                        resultJson,
                        resultText,
                        inputTokens,
                        outputTokens,
                        totalTokens,
                        durationMs,
                        ts(completedAt),
                        ts(completedAt),
                        taskId,
                        workerId) == 1);
    }

    @Override
    public boolean markFailed(String taskId,
                              String workerId,
                              String errorCode,
                              String errorMessage,
                              boolean retryable,
                              String resultText,
                              Instant completedAt,
                              long durationMs) {
        String sql = """
                UPDATE agent_task
                   SET status = 'failed', error_code = ?, error_message = ?, retryable = ?,
                       result_text = ?, duration_ms = ?, completed_at = ?, updated_at = ?
                 WHERE task_id = ? AND worker_id = ? AND status <> 'cancelled'
                """;
        return retryOnContention("markFailed " + taskId,
                () -> jdbc.update(sql,
                        errorCode,
                        truncate(errorMessage, 4000),
                        retryable,
                        resultText,
                        durationMs,
                        ts(completedAt),
                        ts(completedAt),
                        taskId,
                        workerId) == 1);
    }

    @Override
    public boolean cancelIfActive(String taskId, Instant completedAt) {
        String sql = """
                UPDATE agent_task
                   SET status = 'cancelled', completed_at = ?, updated_at = ?
                 WHERE task_id = ? AND status IN ('accepted', 'running')
                """;
        return jdbc.update(sql, ts(completedAt), ts(completedAt), taskId) == 1;
    }

    @Override
    public void deleteIfStatus(String taskId, String status) {
        jdbc.update("DELETE FROM agent_task WHERE task_id = ? AND status = ?", taskId, status);
    }

    @Override
    public void updateCallbackStatus(String taskId, String status, Instant at) {
        jdbc.update("UPDATE agent_task SET callback_status = ?, callback_at = ? WHERE task_id = ?",
                status, ts(at), taskId);
    }

    @Override
    public java.util.List<TaskRecord> findPendingCallbacks(int limit) {
        String sql = "SELECT " + COLUMNS + " FROM agent_task"
                + " WHERE callback_url IS NOT NULL AND callback_status = 'pending'"
                + " ORDER BY completed_at ASC LIMIT ?";
        return jdbc.query(sql, MAPPER, limit);
    }

    @Override
    public boolean claimCallback(String taskId) {
        return jdbc.update("UPDATE agent_task SET callback_status = 'delivering'"
                + " WHERE task_id = ? AND callback_status = 'pending'", taskId) == 1;
    }

    /**
     * 一条调用一行写进 agent_tool_call。{@code INSERT IGNORE} + 唯一键
     * (task_id, attempt, seq)：终态写入重试时不会写出重复的行。
     */
    @Override
    public void saveToolCalls(String taskId, int attempt, List<ToolCallRecord> calls) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        String sql = """
                INSERT IGNORE INTO agent_tool_call
                    (task_id, attempt, seq, tool, args, at_ms, duration_ms, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        List<Object[]> batch = new ArrayList<>(calls.size());
        for (ToolCallRecord call : calls) {
            batch.add(new Object[]{taskId, attempt, call.seq(), call.tool(), call.args(),
                    call.atMs(), call.durationMs(), call.state(), now});
        }
        jdbc.batchUpdate(sql, batch);
    }

    /**
     * 最近一次 attempt 的链：重试过的任务只把最后那一趟返回给调用方，
     * 前面几趟留在表里按 attempt 可查。子表缺失时返回空（不能让任务详情因观测表挂掉）。
     */
    @Override
    public List<ToolCallRecord> findToolCalls(String taskId) {
        String sql = """
                SELECT seq, tool, args, at_ms, duration_ms, state FROM agent_tool_call
                 WHERE task_id = ?
                   AND attempt = (SELECT MAX(attempt) FROM agent_tool_call WHERE task_id = ?)
                 ORDER BY seq ASC
                """;
        try {
            return jdbc.query(sql, (rs, n) -> new ToolCallRecord(rs.getInt("seq"),
                    rs.getString("tool"), rs.getString("args"), rs.getLong("at_ms"),
                    readLong(rs, "duration_ms"), rs.getString("state")), taskId, taskId);
        } catch (RuntimeException e) {
            log.debug("tool calls unavailable for {}: {}", taskId, e.toString());
            return List.of();
        }
    }

    /**
     * 保留期清理：按 {@code created_at} 分批删，每批 {@value #PURGE_BATCH} 行，删到不足一批为止。
     *
     * <p>{@code at_ms} 是「距本次运行开始的毫秒数」不是时刻，所以保留期只能按 {@code created_at} 算。
     * 索引见 {@code db/migration-008-tool-call-retention.sql}；没有索引也只是全表扫，不影响正确性。
     * 子表缺失（老库没执行 migration-007）时按 0 返回：清理失败不该有任何下游影响。
     */
    @Override
    public int deleteToolCallsOlderThan(Instant cutoff) {
        String sql = "DELETE FROM agent_tool_call WHERE created_at < ? LIMIT " + PURGE_BATCH;
        Timestamp before = Timestamp.from(cutoff);
        int total = 0;
        try {
            int deleted;
            do {
                deleted = jdbc.update(sql, before);
                total += deleted;
            } while (deleted == PURGE_BATCH);
        } catch (RuntimeException e) {
            log.debug("agent_tool_call purge skipped: {}", e.toString());
        }
        return total;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

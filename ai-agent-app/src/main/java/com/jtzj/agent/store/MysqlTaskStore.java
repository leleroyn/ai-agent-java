package com.jtzj.agent.store;

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
            callback_url, callback_status, callback_at,
            result_json, result_text, error_code, error_message, retryable,
            input_tokens, output_tokens, total_tokens, duration_ms, attempt,
            created_at, started_at, completed_at, updated_at
            """;

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
                    skill_names, model_name, callback_url, retryable, attempt, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
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
     * Take one queued task.
     *
     * <p>Candidates are read first, then each is claimed with a guarded update. Two workers
     * that pick the same candidate serialise on the row lock and only one gets
     * {@code affected == 1}; the other falls through to the next candidate.
     */
    @Override
    public Optional<TaskRecord> claimNext(String workerId, Duration lease, int peekLimit) {
        List<String> candidates = jdbc.queryForList(
                "SELECT task_id FROM agent_task WHERE status = 'accepted'"
                        + " ORDER BY created_at ASC, task_id ASC LIMIT ?",
                String.class, Math.max(1, peekLimit));
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

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

package com.example.agent.store;

import com.example.agent.model.TaskRecord;
import com.example.agent.model.TaskStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Task persistence, and the work queue itself: {@code agent_task} doubles as the queue, so a
 * queued task survives a restart and multiple instances can compete for work.
 *
 * <p>Claiming uses {@code SELECT candidates} then a conditional {@code UPDATE ... WHERE
 * status='accepted'} rather than {@code FOR UPDATE SKIP LOCKED}, because that clause needs
 * MySQL 8.0.1+ and this schema is verified against 5.7. InnoDB row locks make the conditional
 * update atomic: exactly one worker sees one affected row.
 *
 * <p>All transitions are conditional so a cancelled task is never overwritten by a late worker.
 */
public interface TaskStore {

    /**
     * Insert only when the id is new — the idempotency primitive. The row lands in
     * {@code accepted}, which is exactly the queue state; nothing else is needed to enqueue it.
     *
     * @return true when this call created the row, false when the id already existed
     */
    boolean insertIfAbsent(TaskRecord record);

    Optional<TaskRecord> find(String taskId);

    /** @return false when the task is no longer in {@code accepted} state */
    boolean markRunning(String taskId, Instant startedAt);

    /**
     * Record success. Guarded so that neither a cancellation nor a lease that was handed to
     * another worker can be overwritten by a late result.
     *
     * @param workerId must still own the row
     * @return false when the row was cancelled or is no longer owned by this worker
     */
    boolean markCompleted(String taskId,
                          String workerId,
                          String resultJson,
                          String resultText,
                          Long inputTokens,
                          Long outputTokens,
                          Long totalTokens,
                          Instant completedAt,
                          long durationMs);

    /** @param workerId must still own the row; @return false when cancelled or lease stolen */
    boolean markFailed(String taskId,
                       String workerId,
                       String errorCode,
                       String errorMessage,
                       boolean retryable,
                       String resultText,
                       Instant completedAt,
                       long durationMs);

    /**
     * Move {@code accepted}/{@code running} to {@code cancelled}.
     *
     * @return false when the task is unknown or already terminal
     */
    boolean cancelIfActive(String taskId, Instant completedAt);

    /** Used to roll back a submission that could not be queued. */
    void deleteIfStatus(String taskId, String status);

    /**
     * Atomically take one queued task.
     *
     * @param workerId     identity recorded on the row
     * @param lease        how long this claim stays valid without renewal
     * @param peekLimit    how many candidate ids to inspect before giving up
     * @return the claimed row (already {@code running}), or empty when nothing is queued
     */
    Optional<TaskRecord> claimNext(String workerId, Duration lease, int peekLimit);

    /** Extend this worker's lease. False means the row is no longer ours (lost or cancelled). */
    boolean renewLease(String taskId, String workerId, Duration lease, Instant now);

    /**
     * Return tasks whose lease lapsed back to the queue (the worker died mid-run).
     *
     * @return rows re-queued
     */
    int requeueExpiredLeases(int maxAttempts, Instant now);

    /**
     * Fail tasks whose lease lapsed and that already burned their retries.
     *
     * @return rows failed
     */
    int failExhaustedLeases(int maxAttempts, String message, Instant now);

    /** Queue depth for a status, used for backpressure and health reporting. */
    long countByStatus(TaskStatus status);

    /** Most recently created tasks, for operator inspection. */
    List<TaskRecord> listRecent(int limit);
}

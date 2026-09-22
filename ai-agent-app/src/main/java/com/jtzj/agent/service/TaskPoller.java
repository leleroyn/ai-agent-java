package com.jtzj.agent.service;

import com.jtzj.agent.core.error.AgentException;
import com.jtzj.agent.runtime.AgentTaskRunner;
import com.jtzj.agent.core.error.ErrorCodes;
import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.model.TaskRecord;
import com.jtzj.agent.core.model.TaskStatus;
import com.jtzj.agent.store.TaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes tasks by polling {@code agent_task} — there is no in-memory queue.
 *
 * <p>Each of the {@code maxConcurrent} worker threads loops: claim one queued row, run it,
 * claim the next. A row is only ever held by one worker because claiming is a conditional
 * update, so this is safe across several instances of the service sharing one database.
 *
 * <p>Crash behaviour: a claim carries a lease. If the process dies, the lease lapses and the
 * reaper returns the task to {@code accepted} (up to {@code maxAttempts}), so queued <em>and
 * in-flight</em> work both survive a restart instead of being lost.
 */
@Component
public class TaskPoller implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TaskPoller.class);

    private final TaskStore store;
    private final AgentTaskRunner runner;
    private final AgentProperties props;
    private final ObjectMapper jackson2;
    private final String workerId;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "agent-lease-tasks");
                thread.setDaemon(true);
                return thread;
            });
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final AtomicInteger active = new AtomicInteger();
    /** taskId -> executing thread, so a local cancellation can actually interrupt the run. */
    private final java.util.concurrent.ConcurrentHashMap<String, Thread> executing =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean running;

    public TaskPoller(TaskStore store, AgentTaskRunner runner, AgentProperties props,
                      ObjectMapper jackson2) {
        this.store = store;
        this.runner = runner;
        this.props = props;
        this.jackson2 = jackson2;
        this.workerId = buildWorkerId();
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        String jvm = java.lang.management.ManagementFactory.getRuntimeMXBean().getName()
                .split("@")[0];
        return host + "-" + jvm + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        // Recover anything a previous process left mid-flight before accepting new work.
        reapQuietly();

        int concurrency = Math.max(1, props.getExecution().getMaxConcurrent());
        for (int i = 1; i <= concurrency; i++) {
            Thread thread = new Thread(this::pollLoop, "agent-worker-" + i);
            thread.setDaemon(false);
            workers.add(thread);
            thread.start();
        }

        int reapSeconds = Math.max(5, props.getExecution().getLeaseReapSeconds());
        scheduler.scheduleWithFixedDelay(this::reapQuietly, reapSeconds, reapSeconds, TimeUnit.SECONDS);

        log.info("task poller started workerId={} concurrency={} lease={}s reap={}s maxAttempts={}",
                workerId, concurrency, props.getExecution().getLeaseSeconds(),
                reapSeconds, props.getExecution().getMaxAttempts());
    }

    public int activeCount() {
        return active.get();
    }

    public String workerId() {
        return workerId;
    }

    /**
     * Interrupt this task if a thread in <em>this</em> process is running it.
     *
     * <p>Other instances cannot be interrupted over HTTP-less coordination; for those the row is
     * already cancelled, so the guarded terminal write drops their late result.
     *
     * @return true when a local thread was interrupted
     */
    public boolean interrupt(String taskId) {
        Thread thread = executing.get(taskId);
        if (thread == null) {
            return false;
        }
        thread.interrupt();
        return true;
    }

    private void pollLoop() {
        long baseMs = Math.max(50L, props.getExecution().getPollIntervalMs());
        long maxMs = Math.max(baseMs, props.getExecution().getMaxPollBackoffSeconds() * 1000L);
        long backoffMs = baseMs;

        while (running) {
            Optional<TaskRecord> claimed;
            try {
                claimed = store.claimNext(workerId,
                        Duration.ofSeconds(props.getExecution().getLeaseSeconds()),
                        props.getExecution().getClaimPeek());
            } catch (Exception e) {
                log.warn("poll failed, retrying in {}ms: {}", backoffMs, e.getMessage());
                if (!sleep(backoffMs)) {
                    return;
                }
                continue;
            }

            if (claimed.isEmpty()) {
                if (!sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(maxMs, backoffMs * 2);
                continue;
            }

            backoffMs = baseMs;
            active.incrementAndGet();
            try {
                execute(claimed.get());
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private void execute(TaskRecord task) {
        String taskId = task.getTaskId();
        MDC.put("taskId", taskId);
        executing.put(taskId, Thread.currentThread());
        long startMillis = System.currentTimeMillis();
        Duration lease = Duration.ofSeconds(props.getExecution().getLeaseSeconds());
        ScheduledFuture<?> renewal = scheduleRenewal(taskId, lease);

        try {
            Duration budget = Duration.ofSeconds(
                    task.getTimeoutSeconds() == null || task.getTimeoutSeconds() <= 0
                            ? props.getExecution().getDefaultTimeoutSeconds()
                            : task.getTimeoutSeconds());

            JsonNode schema;
            try {
                schema = parseSchema(task.getOutputSchema());
            } catch (Exception e) {
                store.markFailed(taskId, workerId, ErrorCodes.INVALID_REQUEST,
                        "stored outputSchema could not be parsed: " + e.getMessage(),
                        false, null, Instant.now(), 0L);
                return;
            }

            AgentTaskRunner.RunOutcome outcome = runner.run(taskId, task.getInstruction(),
                    schema, splitSkills(task.getSkillNames()), budget, task.getModelName());

            long durationMs = System.currentTimeMillis() - startMillis;
            Long inputTokens = null;
            Long outputTokens = null;
            Long totalTokens = null;
            if (outcome.usage() != null) {
                inputTokens = (long) outcome.usage().getInputTokens();
                outputTokens = (long) outcome.usage().getOutputTokens();
                totalTokens = (long) outcome.usage().getTotalTokens();
            }

            boolean written = store.markCompleted(taskId, workerId,
                    outcome.result() == null ? null : outcome.result().toString(),
                    outcome.text(),
                    inputTokens, outputTokens, totalTokens,
                    Instant.now(), durationMs);
            if (written) {
                log.info("task {} completed in {}ms tokens={}/{} hasResult={}",
                        taskId, durationMs, inputTokens, outputTokens, outcome.result() != null);
            } else {
                log.info("task {} finished but no longer owned (cancelled or lease stolen);"
                        + " result not written", taskId);
            }
        } catch (AgentException e) {
            long durationMs = System.currentTimeMillis() - startMillis;
            log.warn("task {} failed code={} retryable={} message={}",
                    taskId, e.getCode(), e.isRetryable(), e.getMessage());
            store.markFailed(taskId, workerId, e.getCode(), e.getMessage(),
                    e.isRetryable(), null, Instant.now(), durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMillis;
            log.error("task {} failed unexpectedly", taskId, e);
            store.markFailed(taskId, workerId, ErrorCodes.AGENT_EXECUTION_FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    false, null, Instant.now(), durationMs);
        } finally {
            renewal.cancel(false);
            executing.remove(taskId);
            // A cancellation interrupt leaves the flag set on this pooled-by-name thread; clear it
            // so the next task this thread claims is not born already-interrupted.
            Thread.interrupted();
            MDC.remove("taskId");
        }
    }

    /** Keep the claim alive for the duration of a long run. */
    private ScheduledFuture<?> scheduleRenewal(String taskId, Duration lease) {
        long periodSeconds = Math.max(5L, lease.toSeconds() / 3);
        return scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!store.renewLease(taskId, workerId, lease, Instant.now())) {
                    log.warn("lease renewal lost task {} (cancelled or lease stolen)", taskId);
                }
            } catch (Exception e) {
                log.warn("lease renewal failed for {}: {}", taskId, e.getMessage());
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /**
     * Return work abandoned by dead workers to the queue, and fail what exhausted its retries.
     * Also runs at startup, which replaces the old "fail everything on boot" reaper.
     */
    void reapQuietly() {
        try {
            Instant now = Instant.now();
            int maxAttempts = Math.max(1, props.getExecution().getMaxAttempts());
            int exhausted = store.failExhaustedLeases(maxAttempts,
                    "worker died and the task already used its attempts", now);
            int requeued = store.requeueExpiredLeases(maxAttempts, now);
            if (requeued > 0 || exhausted > 0) {
                log.warn("lease reaper: requeued={} exhausted={}", requeued, exhausted);
            }
        } catch (Exception e) {
            log.warn("lease reaper failed: {}", e.getMessage());
        }
    }

    /** Stored comma-separated skill names back into a list; null or blank means "all installed". */
    private static List<String> splitSkills(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> names = new java.util.ArrayList<>();
        for (String part : stored.split(",")) {
            String name = part.trim();
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private JsonNode parseSchema(String stored) throws Exception {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        return jackson2.readTree(stored);
    }

    /** @return false when interrupted and the loop should exit */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void destroy() {
        running = false;
        for (Thread thread : workers) {
            thread.interrupt();
        }
        scheduler.shutdownNow();
        // Rows left in 'running' keep their lease; the next process requeues them once it lapses.
        log.info("task poller stopped; in-flight rows will be requeued after their lease expires");
    }

    /** Exposed for the health endpoint. */
    public long queuedCount() {
        return store.countByStatus(TaskStatus.ACCEPTED);
    }
}

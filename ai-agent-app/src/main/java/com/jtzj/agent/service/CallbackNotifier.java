package com.jtzj.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtzj.agent.api.ApiResponse;
import com.jtzj.agent.core.model.TaskRecord;
import com.jtzj.agent.core.model.TaskStatusView;
import com.jtzj.agent.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fires an HTTP POST to the task's {@code callbackUrl} when it reaches a terminal state.
 *
 * <p>Delivery is fire-and-forget on a dedicated 2-thread pool: the worker thread that
 * completed the task is never blocked by a slow or unreachable callback endpoint.
 *
 * <p>The payload mirrors the {@code GET /task/{taskId}} response body (without the envelope)
 * so the receiver does not need a second request.
 *
 * <p>Callback URL validation (must be http/https, ≤1024 chars) happens at submit time in
 * {@link AgentTaskService}; this class assumes the stored value is well-formed.
 */
@Component
public class CallbackNotifier {

    private static final Logger log = LoggerFactory.getLogger(CallbackNotifier.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int COMPENSATION_BATCH = 50;

    private final TaskStore store;
    private final ObjectMapper jackson2;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final java.util.concurrent.ScheduledExecutorService compensationScheduler;

    public CallbackNotifier(TaskStore store, ObjectMapper jackson2) {
        this.store = store;
        this.jackson2 = jackson2;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "callback-notify");
            t.setDaemon(true);
            return t;
        });
        // Compensation: periodically scan for pending callbacks (user-manually set or crash-recovered).
        this.compensationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "callback-compensation");
            t.setDaemon(true);
            return t;
        });
        this.compensationScheduler.scheduleWithFixedDelay(
                this::compensate, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Submit an async callback if the task has a callbackUrl configured.
     * Called after the task row is already in its terminal state in the DB.
     */
    public void notifyIfConfigured(TaskRecord task) {
        String url = task.getCallbackUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        executor.submit(() -> deliver(task));
    }

    /**
     * Compensation scan: find rows with callback_status='pending' and deliver them.
     * Runs every 30s. Covers: user manually reset to pending, or process crashed
     * after terminal write but before immediate delivery.
     */
    private void compensate() {
        try {
            java.util.List<TaskRecord> pendings = store.findPendingCallbacks(COMPENSATION_BATCH);
            if (pendings.isEmpty()) {
                return;
            }
            int claimed = 0;
            for (TaskRecord task : pendings) {
                // Atomic claim: only one instance wins, others skip (multi-instance safe).
                if (store.claimCallback(task.getTaskId())) {
                    claimed++;
                    deliver(task);
                }
            }
            if (claimed > 0) {
                log.info("callback compensation: found={}, claimed={}", pendings.size(), claimed);
            }
        } catch (Exception e) {
            log.warn("callback compensation scan failed: {}", e.getMessage());
        }
    }

    private void deliver(TaskRecord task) {
        // Re-read from DB to ensure payload is identical to GET /task/{id} response data.
        TaskRecord fresh = store.find(task.getTaskId()).orElse(task);
        String payload;
        try {
            TaskStatusView view = TaskStatusView.of(fresh, jackson2);
            payload = jackson2.writeValueAsString(ApiResponse.ok(view));
        } catch (Exception e) {
            log.error("callback serialize failed task={}: {}", task.getTaskId(), e.getMessage());
            store.updateCallbackStatus(task.getTaskId(), "failed", Instant.now());
            return;
        }
        Instant now = Instant.now();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(fresh.getCallbackUrl()))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                store.updateCallbackStatus(task.getTaskId(), "success", now);
                log.info("callback success task={} url={} httpStatus={}", task.getTaskId(), fresh.getCallbackUrl(), code);
            } else {
                store.updateCallbackStatus(task.getTaskId(), "failed", now);
                log.warn("callback failed task={} url={} httpStatus={}", task.getTaskId(), fresh.getCallbackUrl(), code);
            }
        } catch (Exception e) {
            store.updateCallbackStatus(task.getTaskId(), "failed", now);
            log.warn("callback error task={} url={}: {}", task.getTaskId(), fresh.getCallbackUrl(), e.getMessage());
        }
    }
}

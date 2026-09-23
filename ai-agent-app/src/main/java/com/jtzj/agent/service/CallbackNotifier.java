package com.jtzj.agent.service;

import com.jtzj.agent.core.model.TaskRecord;
import com.jtzj.agent.core.model.TaskStatus;
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
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final java.util.concurrent.ScheduledExecutorService compensationScheduler;

    public CallbackNotifier(TaskStore store) {
        this.store = store;
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
        String payload = buildPayload(task);
        Instant now = Instant.now();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(task.getCallbackUrl()))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                store.updateCallbackStatus(task.getTaskId(), "success", now);
                log.info("callback success task={} url={} httpStatus={}", task.getTaskId(), task.getCallbackUrl(), code);
            } else {
                store.updateCallbackStatus(task.getTaskId(), "failed", now);
                log.warn("callback failed task={} url={} httpStatus={}", task.getTaskId(), task.getCallbackUrl(), code);
            }
        } catch (Exception e) {
            store.updateCallbackStatus(task.getTaskId(), "failed", now);
            log.warn("callback error task={} url={}: {}", task.getTaskId(), task.getCallbackUrl(), e.getMessage());
        }
    }

    private String buildPayload(TaskRecord t) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        json(sb, "taskId", t.getTaskId()); sb.append(',');
        json(sb, "status", t.getStatus() == null ? null : t.getStatus().wire());
        if (t.getResultText() != null) { sb.append(','); json(sb, "resultText", t.getResultText()); }
        if (t.getResultJson() != null) { sb.append(','); json(sb, "result", t.getResultJson(), true); }
        if (t.getErrorCode() != null) {
            sb.append(",\"error\":{");
            json(sb, "code", t.getErrorCode()); sb.append(',');
            json(sb, "message", t.getErrorMessage());
            sb.append(",\"retryable\":").append(Boolean.TRUE.equals(t.getRetryable()));
            sb.append('}');
        }
        if (t.getMetadata() != null) { sb.append(','); json(sb, "metadata", t.getMetadata(), true); }
        if (t.getModelName() != null) { sb.append(','); json(sb, "model", t.getModelName()); }
        if (t.getDurationMs() != null) { sb.append(",\"durationMs\":").append(t.getDurationMs()); }
        if (t.getTotalTokens() != null) { sb.append(",\"totalTokens\":").append(t.getTotalTokens()); }
        sb.append('}');
        return sb.toString();
    }

    private static void json(StringBuilder sb, String key, String value) {
        json(sb, key, value, false);
    }

    private static void json(StringBuilder sb, String key, String value, boolean rawValue) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else if (rawValue) {
            sb.append(value); // assume it's already valid JSON
        } else {
            escapeJson(sb, value);
        }
    }

    private static void escapeJson(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}

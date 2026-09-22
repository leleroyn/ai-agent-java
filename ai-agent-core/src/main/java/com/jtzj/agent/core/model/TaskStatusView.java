package com.jtzj.agent.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Response to GET /api/v1/agent/task/{taskId}. Null fields are omitted.
 *
 * <p>{@code result} is typed as plain {@link Object} (a Map/List tree) rather than a Jackson
 * node type on purpose: Spring Boot 4 serialises it with Jackson 3, while the stored text was
 * parsed with Jackson 2. A neutral tree keeps the two libraries from meeting on the wire type.
 *
 * @param result     structured result, present when {@code outputSchema} was supplied and the run succeeded
 * @param resultText final assistant text, best-effort for every terminal run
 * @param metadata   the caller's own business context, echoed back verbatim so a poll response
 *                   can be matched to the originating order without keeping a side map or
 *                   querying {@code agent_task.business_meta}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskStatusView(
        String taskId,
        String status,
        Object metadata,
        Object result,
        String resultText,
        TaskError error,
        TaskUsage usage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Long durationMs) {

    /**
     * Map a stored record onto the wire form.
     *
     * @param jackson2 mapper used to re-parse the stored {@code result_json}; a corrupt value
     *                 degrades to the raw text instead of throwing
     */
    public static TaskStatusView of(TaskRecord r, ObjectMapper jackson2) {
        TaskError err = r.getErrorCode() == null
                ? null
                : new TaskError(r.getErrorCode(), r.getErrorMessage(), Boolean.TRUE.equals(r.getRetryable()));

        TaskUsage usage = null;
        if (r.getInputTokens() != null || r.getOutputTokens() != null || r.getTotalTokens() != null) {
            usage = new TaskUsage(r.getInputTokens(), r.getOutputTokens(), r.getTotalTokens());
        }

        Object result = null;
        if (r.getResultJson() != null && !r.getResultJson().isBlank()) {
            try {
                result = jackson2.readValue(r.getResultJson(), Object.class);
            } catch (Exception e) {
                result = r.getResultJson();
            }
        }

        // Echoed as stored, same neutral-tree approach as result: a corrupt value degrades to
        // the raw text rather than failing the whole read.
        Object metadata = null;
        if (r.getMetadata() != null && !r.getMetadata().isBlank()) {
            try {
                metadata = jackson2.readValue(r.getMetadata(), Object.class);
            } catch (Exception e) {
                metadata = r.getMetadata();
            }
        }

        return new TaskStatusView(
                r.getTaskId(),
                r.getStatus().wire(),
                metadata,
                result,
                r.getResultText(),
                err,
                usage,
                r.getCreatedAt(),
                r.getStartedAt(),
                r.getCompletedAt(),
                r.getDurationMs());
    }
}

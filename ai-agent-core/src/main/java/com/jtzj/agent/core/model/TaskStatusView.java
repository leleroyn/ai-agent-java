package com.jtzj.agent.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @param tools      工具调用链数组，一条调用一个元素（顺序即调用顺序）；没记时该键不出现
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
        Object tools,
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

        // 调用链来自子表 agent_tool_call 的行（一条调用一个元素）。Map 而不是 record：
        // args / ms 可能为 null，Map 逐字段省略，响应里不出现空键。
        Object tools = null;
        if (r.getToolCalls() != null && !r.getToolCalls().isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>(r.getToolCalls().size());
            for (ToolCallRecord call : r.getToolCalls()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", call.seq());
                row.put("tool", call.tool());
                if (call.args() != null) {
                    row.put("args", call.args());
                }
                row.put("atMs", call.atMs());
                if (call.durationMs() != null) {
                    row.put("ms", call.durationMs());
                }
                row.put("state", call.state());
                rows.add(row);
            }
            tools = rows;
        }

        return new TaskStatusView(
                r.getTaskId(),
                r.getStatus().wire(),
                metadata,
                result,
                r.getResultText(),
                err,
                usage,
                tools,
                r.getCreatedAt(),
                r.getStartedAt(),
                r.getCompletedAt(),
                r.getDurationMs());
    }
}

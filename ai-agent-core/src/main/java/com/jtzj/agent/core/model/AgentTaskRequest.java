package com.jtzj.agent.core.model;

import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

/**
 * Task submission payload.
 *
 * <p>JSON types here are Jackson <b>3</b> ({@code tools.jackson}) because Spring Boot 4
 * deserialises request bodies with Jackson 3. AgentScope speaks Jackson 2; the conversion
 * happens in {@code AgentTaskService}.
 *
 * @param taskId       optional idempotency key supplied by the caller; generated when absent.
 *                     Re-submitting an existing id returns the current state, it does not re-run.
 * @param instruction  natural-language instruction, required
 * @param outputSchema optional JSON Schema. When present the agent must answer with JSON
 *                     conforming to it; absent means free text in {@code resultText}.
 * @param skills       optional skill names to expose to this task. Absent means every installed
 *                     skill is visible; an unknown name is rejected at submit time rather than
 *                     silently ignored.
 * @param model        main-model profile to use ({@code flash} / {@code pro}). Absent means the
 *                     configured default ({@code agent.default-model}); an unknown name is
 *                     rejected at submit time rather than silently falling back.
 * @param options      per-task overrides
 * @param metadata     opaque business context, size-checked only, never interpreted
 */
public record AgentTaskRequest(
        String taskId,
        @NotBlank(message = "instruction must not be blank") String instruction,
        JsonNode outputSchema,
        java.util.List<String> skills,
        String model,
        Options options,
        JsonNode metadata) {

    /**
     * @param sync           when true the HTTP call blocks until the task settles
     * @param timeoutSeconds wall-clock budget including queue wait; clamped to
     *                       {@code agent.execution.max-timeout-seconds}
     */
    public record Options(Boolean sync, Integer timeoutSeconds) {
    }

    public boolean syncRequested() {
        return options != null && Boolean.TRUE.equals(options.sync());
    }
}

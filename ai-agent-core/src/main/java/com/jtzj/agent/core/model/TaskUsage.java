package com.jtzj.agent.core.model;

/** Token usage reported by the model for this task. */
public record TaskUsage(Long inputTokens, Long outputTokens, Long totalTokens) {
}

package com.example.agent.model;

/**
 * @param retryable when true the caller may re-submit under the same {@code taskId}
 *                  after deleting/cancelling it, or simply retry the request
 */
public record TaskError(String code, String message, boolean retryable) {
}

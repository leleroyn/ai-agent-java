package com.example.agent.agent;

/** Stable error codes surfaced in the API's {@code error.code} field. */
public final class ErrorCodes {

    /** Wall-clock budget exhausted; retryable. */
    public static final String AGENT_TIMEOUT = "AGENT_TIMEOUT";
    /** Agent run failed (model error, tool error, unexpected exception); not retryable by default. */
    public static final String AGENT_EXECUTION_FAILED = "AGENT_EXECUTION_FAILED";
    /** Agent finished but produced nothing usable for the requested outputSchema. */
    public static final String NO_RESULT = "NO_RESULT";
    /** The run was interrupted (cancellation or pool shutdown). */
    public static final String AGENT_INTERRUPTED = "AGENT_INTERRUPTED";
    /** Request body rejected (size limits, malformed schema). */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    /** Queue full and the caller asked for immediate admission instead of queueing. */
    public static final String CONCURRENCY_LIMITED = "CONCURRENCY_LIMITED";

    private ErrorCodes() {
    }
}

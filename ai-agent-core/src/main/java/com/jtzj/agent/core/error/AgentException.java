package com.jtzj.agent.core.error;

/** Carries the error code / retryability that should land on the task record. */
public class AgentException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public AgentException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public AgentException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

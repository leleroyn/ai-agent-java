package com.jtzj.agent.core.error;

/** Carries the error code / retryability that should land on the task record. */
public class AgentException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    /**
     * 失败发生时这条任务已经发生的工具调用链（JSON，见 core.support.ToolTrace）。由 runner 在抛出
     * 前附上，让失败的任务也看得到「跑到哪一步断的」。可为 null。
     */
    private String toolTrace;

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

    public String getToolTrace() {
        return toolTrace;
    }

    public void setToolTrace(String toolTrace) {
        this.toolTrace = toolTrace;
    }
}

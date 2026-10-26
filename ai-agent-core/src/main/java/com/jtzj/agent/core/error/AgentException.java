package com.jtzj.agent.core.error;

import com.jtzj.agent.core.model.ToolCallRecord;
import java.util.List;

/** Carries the error code / retryability that should land on the task record. */
public class AgentException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    /**
     * 失败发生时这趟任务已经发生的工具调用（见 core.support.ToolTrace）。由 runner 在抛出前附上，
     * 让失败的任务也看得到「断在哪一次调用」。可为 null。
     */
    private List<ToolCallRecord> toolCalls;

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

    public List<ToolCallRecord> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCallRecord> toolCalls) {
        this.toolCalls = toolCalls;
    }
}

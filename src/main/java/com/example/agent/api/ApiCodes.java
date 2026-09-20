package com.example.agent.api;

/**
 * Numeric API result codes carried by {@link ApiResponse#getCode()}.
 *
 * <p>These describe the outcome of <em>the API call itself</em>. They are deliberately a separate
 * namespace from the task's execution error labels ({@code AGENT_TIMEOUT} and friends, stored in
 * {@code agent_task.error_code}): a task that failed is still a successful query, so it comes
 * back with {@link #OK} and the failure sits in {@code data.status} / {@code data.error}.
 *
 * <p>Ranges are reserved so new codes do not collide:
 * {@code 0} success, {@code 1xxx} request problems, {@code 2xxx} task state, {@code 3xxx}
 * capacity, {@code 9xxx} server faults.
 */
public final class ApiCodes {

    /** Call succeeded. Details, if any, are in {@code data}. */
    public static final int OK = 0;

    /** Body rejected: blank or oversized fields, bad taskId, unknown skill name, unparsable JSON. */
    public static final int INVALID_REQUEST = 1001;

    /** No handler is mapped for this path — a mistyped or removed endpoint. Answered HTTP 404. */
    public static final int NOT_FOUND = 1002;

    /** No task with that id. */
    public static final int TASK_NOT_FOUND = 2001;

    /** Task exists but is already {@code completed}/{@code failed}/{@code cancelled}. */
    public static final int TASK_NOT_CANCELABLE = 2002;

    /** Rejected because too many tasks are already queued. */
    public static final int QUEUE_LIMITED = 3001;

    /** Unhandled server fault. */
    public static final int INTERNAL_ERROR = 9999;

    private ApiCodes() {
    }
}

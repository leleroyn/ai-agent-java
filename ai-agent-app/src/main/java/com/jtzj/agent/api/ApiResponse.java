package com.jtzj.agent.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one response envelope every endpoint returns.
 *
 * <p>Business outcome travels in {@code code}, not in the HTTP status. Every handled path answers
 * HTTP 200 — including rejections such as a bad parameter, an unknown task or a non-cancellable
 * task — so a client reads a single field instead of mapping status codes onto business meaning.
 *
 * <p>The one exception is an unhandled server fault, which still surfaces as HTTP 500 alongside
 * {@code code=9999}: turning crashes into 200s would silence gateway and monitoring alerts.
 *
 * <p>Note that a <em>task</em> failing is not an API failure. Querying a failed task returns
 * {@code code=0} with the failure described in {@code data.status} and {@code data.error}.
 *
 * @param code    numeric result code, see {@link ApiCodes}; {@code 0} means success
 * @param message human-readable detail; absent when there is nothing to add
 * @param data    payload on success; absent on errors
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data) {

    /** Success carrying a payload. */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ApiCodes.OK, null, data);
    }

    /** Success with nothing to return, e.g. a completed cancellation. */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(ApiCodes.OK, null, null);
    }

    /** Rejection or fault; no data is attached. */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}

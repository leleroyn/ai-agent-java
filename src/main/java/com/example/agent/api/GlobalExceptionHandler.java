package com.example.agent.api;

import com.example.agent.agent.AgentException;
import com.example.agent.agent.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Wraps every failure in the standard {@link ApiResponse} envelope.
 *
 * <p>Rejections answer HTTP 200 and are distinguished by {@code code} alone, so callers never have
 * to map status codes onto business meaning. HTTP status is retained only where it still means
 * something to infrastructure: an unhandled fault answers 500 so gateways and alerting keep
 * working.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ApiResponse<Void>> handleAgent(AgentException e) {
        int apiCode = apiCodeFor(e.getCode());
        if (apiCode == ApiCodes.INVALID_REQUEST || apiCode == ApiCodes.QUEUE_LIMITED) {
            log.info("request rejected code={} apiCode={} message={}",
                    e.getCode(), apiCode, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(apiCode, e.getMessage()));
        }
        // An execution-side label reaching the API boundary is a server-side problem worth a log
        // at warn level and an alertable status.
        log.warn("agent error code={} message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ApiCodes.INTERNAL_ERROR,
                        e.getCode() + ": " + e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        // Annotation messages already name the field, so do not prefix it again.
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage() != null
                        ? error.getDefaultMessage()
                        : error.getField() + " is invalid")
                .findFirst()
                .orElse("request validation failed");
        return rejection(detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return rejection("request body must be valid JSON");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(
                ApiCodes.INTERNAL_ERROR,
                e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    /** Bad input is a business outcome: HTTP 200, code says what was wrong. */
    private static ResponseEntity<ApiResponse<Void>> rejection(String message) {
        return ResponseEntity.ok(ApiResponse.error(ApiCodes.INVALID_REQUEST, message));
    }

    /**
     * Translate the internal error label onto the numeric API code.
     *
     * <p>Only two labels are meaningful at the API boundary; anything else ({@code AGENT_TIMEOUT}
     * and friends) belongs to a task's recorded failure, not to this call, so it maps to a server
     * fault.
     */
    private static int apiCodeFor(String code) {
        return switch (code == null ? "" : code) {
            case ErrorCodes.INVALID_REQUEST -> ApiCodes.INVALID_REQUEST;
            case ErrorCodes.CONCURRENCY_LIMITED -> ApiCodes.QUEUE_LIMITED;
            default -> ApiCodes.INTERNAL_ERROR;
        };
    }
}

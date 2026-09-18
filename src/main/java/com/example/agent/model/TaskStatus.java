package com.example.agent.model;

import java.util.Locale;

/**
 * Task lifecycle. Wire form is lowercase, e.g. {@code accepted}.
 *
 * <pre>
 * accepted -> running -> completed
 *                     -> failed
 *                     -> cancelled
 * </pre>
 */
public enum TaskStatus {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** Lowercase form used in HTTP payloads and the status column. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a wire value.
     *
     * @throws IllegalArgumentException when {@code value} is null/blank/unknown
     */
    public static TaskStatus fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        return TaskStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}

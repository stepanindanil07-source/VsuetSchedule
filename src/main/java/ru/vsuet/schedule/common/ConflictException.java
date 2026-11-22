package ru.vsuet.schedule.common;

/** The request contradicts the current state (a duplicate, an overlap, a disabled source). HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

package ru.vsuet.schedule.common;

/** A requested resource does not exist (or is no longer published). Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

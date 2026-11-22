package ru.vsuet.schedule.common;

/** Missing or wrong credentials for an administrative endpoint. HTTP 401. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}

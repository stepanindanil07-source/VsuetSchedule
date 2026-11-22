package ru.vsuet.schedule.common;

/** The request is well-formed but its content is not acceptable (e.g. a first week that is not a Monday). HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}

package ru.vsuet.schedule.importer.application;

/** A schedule file could not be downloaded (after all retries, or because retrying would not help). */
public class FileDownloadException extends RuntimeException {

    private final boolean retryable;

    FileDownloadException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    FileDownloadException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    boolean isRetryable() {
        return retryable;
    }
}

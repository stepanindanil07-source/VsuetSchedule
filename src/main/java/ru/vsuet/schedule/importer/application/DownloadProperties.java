package ru.vsuet.schedule.importer.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings for downloading schedule files. vsuet.ru sometimes does not answer a connection at all (seen while
 * developing: the same request timed out, then succeeded a moment later), hence several attempts.
 *
 * @param connectTimeout max time to establish the connection, per attempt
 * @param readTimeout    max time waiting for response data, per attempt
 * @param maxAttempts    total attempts including the first one
 * @param retryDelay     pause before the 2nd attempt; it grows linearly (delay, 2*delay, ...)
 * @param maxSize        files larger than this are rejected instead of being loaded into memory
 */
@ConfigurationProperties("vsuet.import.download")
public record DownloadProperties(
        @DefaultValue("15s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout,
        @DefaultValue("4") int maxAttempts,
        @DefaultValue("3s") Duration retryDelay,
        @DefaultValue("20MB") DataSize maxSize) {

    public DownloadProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("vsuet.import.download.max-attempts must be >= 1");
        }
    }
}

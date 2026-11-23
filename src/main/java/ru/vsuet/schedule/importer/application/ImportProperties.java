package ru.vsuet.schedule.importer.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param minEntriesRatio a new file with fewer than this share of the previous import's lessons (same source and
 *                        period) is rejected as suspicious: a half-empty file is more likely a publishing mistake
 *                        than a real schedule change
 * @param healthMaxAge    the import health check reports DOWN when an enabled source has had no successful (or
 *                        "file unchanged") import for longer than this; the scheduler runs hourly, so the default
 *                        tolerates a few missed runs before anyone is alerted
 * @param runOnStartup    import every enabled source right after the application has started instead of waiting for
 *                        the next scheduled run (an empty database would otherwise stay empty for up to an hour)
 */
@ConfigurationProperties("vsuet.import")
public record ImportProperties(@DefaultValue("0.5") double minEntriesRatio,
                               @DefaultValue("6h") Duration healthMaxAge,
                               @DefaultValue("false") boolean runOnStartup) {
}

package ru.vsuet.schedule.importer.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param minEntriesRatio a new file with fewer than this share of the previous import's lessons (same source and
 *                        period) is rejected as suspicious: a half-empty file is more likely a publishing mistake
 *                        than a real schedule change
 */
@ConfigurationProperties("vsuet.import")
public record ImportProperties(@DefaultValue("0.5") double minEntriesRatio) {
}

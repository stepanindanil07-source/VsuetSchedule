package ru.vsuet.schedule.admin.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param token bearer token of the administrative API ({@code /api/v1/admin/**}). Empty (the default) disables that
 *              API completely: its URLs answer 404 as if they did not exist. Set it with the environment variable
 *              {@code VSUET_ADMIN_TOKEN}.
 */
@ConfigurationProperties("vsuet.admin")
public record AdminProperties(String token) {
}

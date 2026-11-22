package ru.vsuet.schedule.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.servlet.HandlerInterceptor;
import ru.vsuet.schedule.admin.application.AdminProperties;
import ru.vsuet.schedule.common.NotFoundException;
import ru.vsuet.schedule.common.UnauthorizedException;

/**
 * Guards {@code /api/v1/admin/**} with one shared bearer token. No token configured = the API is switched off and does
 * not reveal that it exists (404). The comparison takes the same time whatever prefix of the token matches. Exceptions
 * thrown here are turned into problem+json by the common exception handler.
 */
final class AdminAccessInterceptor implements HandlerInterceptor {

    private static final String PREFIX = "Bearer ";

    private final AdminProperties properties;

    AdminAccessInterceptor(AdminProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String expected = properties.token();
        if (expected == null || expected.isBlank()) {
            throw new NotFoundException("Not found");
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX)
                || !MessageDigest.isEqual(header.substring(PREFIX.length()).strip().getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Missing or invalid admin token");
        }
        return true;
    }
}

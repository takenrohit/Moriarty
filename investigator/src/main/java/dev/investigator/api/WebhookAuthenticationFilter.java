package dev.investigator.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.WebhookProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Webhook_Authenticator: requires the shared webhook secret on {@code POST /api/v1/alerts} and
 * {@code POST /api/v1/alerts/**} when one is configured (Req 10.1 to 10.7, CP-51).
 *
 * <ul>
 *   <li>Credential: {@code Authorization: Bearer <secret>} or {@code X-Webhook-Token: <secret>}; either
 *       matching header is enough.
 *   <li>Comparison: SHA-256 digests of both values compared with {@link MessageDigest#isEqual}, so the
 *       time taken depends on neither the content nor the length of the presented value (Req 10.3).
 *   <li>No secret configured: every request passes (Req 10.5).
 *   <li>Other methods and paths (health, incidents, reports, pages) are never challenged (Req 10.6).
 *   <li>Rejection: 401 JSON with a fixed message; the presented value is neither echoed nor logged.
 * </ul>
 *
 * <p>Runs before {@link RequestSizeLimitFilter}, so an unauthenticated body is never read.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class WebhookAuthenticationFilter extends OncePerRequestFilter {

    static final String ALERTS_PATH = "/api/v1/alerts";
    static final String TOKEN_HEADER = "X-Webhook-Token";
    static final String UNAUTHORIZED_MESSAGE = "missing or invalid webhook credential";
    private static final String BEARER_PREFIX = "bearer ";
    private static final Logger log = LoggerFactory.getLogger(WebhookAuthenticationFilter.class);

    private final ObjectMapper objectMapper;
    private final byte[] secretDigest;

    public WebhookAuthenticationFilter(WebhookProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.secretDigest = properties.authenticationEnabled() ? sha256(properties.secret()) : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return secretDigest == null || !isAlertIntake(request);
    }

    /** Whether {@code request} is a POST to an alert intake endpoint. */
    static boolean isAlertIntake(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        // Decoded, without ;parameters and with duplicate slashes collapsed, as Spring MVC matches it.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return path.equals(ALERTS_PATH) || path.startsWith(ALERTS_PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(bearerToken(request)) || matches(request.getHeader(TOKEN_HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Rejected unauthenticated alert webhook request to {}", request.getRequestURI());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(UNAUTHORIZED_MESSAGE));
    }

    private boolean matches(String presented) {
        return presented != null && MessageDigest.isEqual(secretDigest, sha256(presented));
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() < BEARER_PREFIX.length()
                || !header.substring(0, BEARER_PREFIX.length()).toLowerCase(Locale.ROOT).equals(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

package com.elecsarthi.security.jwt;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.Clock;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final String ACTION_REGISTRATION_JTI_INVALIDATED = "REGISTRATION_JTI_INVALIDATED";

    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final SecretKey key;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final java.util.Set<String> invalidatedRegistrationJtis = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public JwtTokenProvider(AppProperties appProperties, JdbcTemplate jdbcTemplate, Clock clock) {
        this.appProperties = appProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(appProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UUID sessionId, Long userId, String username, Role role, Long customerAccountId) {
        Instant now = clock.instant();
        Instant expiry = now.plus(appProperties.getJwt().getAccessTokenExpirationMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .issuer(appProperties.getJwt().getIssuer())
                .subject(username)
                .claim("typ", "ACCESS")
                .claim("sid", sessionId.toString())
                .claim("uid", userId)
                .claim("role", role.name())
                .claim("cid", customerAccountId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public String generateRegistrationToken(Long userId, String username, Role role, Long customerAccountId) {
        Instant now = clock.instant();
        Instant expiry = now.plus(5, ChronoUnit.MINUTES); // 5 minutes lifetime max

        return Jwts.builder()
                .issuer(appProperties.getJwt().getIssuer())
                .subject(username)
                .claim("typ", "REGISTRATION")
                .claim("jti", UUID.randomUUID().toString())
                .claim("uid", userId)
                .claim("role", role.name())
                .claim("cid", customerAccountId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public void invalidateRegistrationJti(String jti) {
        if (jti != null && !jti.isBlank()) {
            String normalizedJti = jti.trim();
            try {
                String detailsJson = objectMapper.writeValueAsString(java.util.Map.of("jti", normalizedJti));
                jdbcTemplate.update(
                        "INSERT INTO audit_log (action_type, details, outcome, created_at) VALUES (?, ?::jsonb, 'SUCCESS', clock_timestamp())",
                        ACTION_REGISTRATION_JTI_INVALIDATED, detailsJson
                );
                invalidatedRegistrationJtis.add(normalizedJti);
            } catch (Exception e) {
                log.error("Failed to persist registration JTI invalidation to audit_log: {}", e.getMessage());
                throw new IllegalStateException("Failed to persist registration token single-use invalidation", e);
            }
        }
    }

    public boolean isRegistrationJtiInvalid(String jti) {
        if (jti == null || jti.isBlank()) {
            return true;
        }
        String normalizedJti = jti.trim();
        if (invalidatedRegistrationJtis.contains(normalizedJti)) {
            return true;
        }
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM audit_log WHERE action_type = 'REGISTRATION_JTI_INVALIDATED' AND details->>'jti' = ?)",
                    Boolean.class, normalizedJti
            );
            if (Boolean.TRUE.equals(exists)) {
                invalidatedRegistrationJtis.add(normalizedJti);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Database error while checking registration JTI invalidation; failing closed to reject token replay: {}", e.getMessage());
            return true; // Fail closed: treat as invalid on error to prevent token replay
        }
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

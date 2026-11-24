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
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final SecretKey key;
    private final java.util.Set<String> invalidatedRegistrationJtis = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public JwtTokenProvider(AppProperties appProperties, JdbcTemplate jdbcTemplate) {
        this.appProperties = appProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.key = Keys.hmacShaKeyFor(appProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UUID sessionId, Long userId, String username, Role role, Long customerAccountId) {
        Instant now = Instant.now();
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
        Instant now = Instant.now();
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
            invalidatedRegistrationJtis.add(jti);
            try {
                jdbcTemplate.update(
                        "INSERT INTO audit_log (action_type, details, outcome, created_at) VALUES ('REGISTRATION_JTI_INVALIDATED', ?::jsonb, 'SUCCESS', clock_timestamp())",
                        "{\"jti\":\"" + jti.trim() + "\"}"
                );
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isRegistrationJtiInvalid(String jti) {
        if (jti == null || jti.isBlank()) {
            return true;
        }
        if (invalidatedRegistrationJtis.contains(jti)) {
            return true;
        }
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM audit_log WHERE action_type = 'REGISTRATION_JTI_INVALIDATED' AND details->>'jti' = ?)",
                    Boolean.class, jti.trim()
            );
            if (Boolean.TRUE.equals(exists)) {
                invalidatedRegistrationJtis.add(jti);
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
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

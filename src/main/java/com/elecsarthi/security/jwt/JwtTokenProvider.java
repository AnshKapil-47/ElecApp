package com.elecsarthi.security.jwt;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private final AppProperties appProperties;
    private final SecretKey key;

    public JwtTokenProvider(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.key = Keys.hmacShaKeyFor(appProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UUID sessionId, Long userId, String username, Role role, Long customerAccountId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(appProperties.getJwt().getAccessTokenExpirationMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .issuer(appProperties.getJwt().getIssuer())
                .subject(username)
                .claim("sid", sessionId.toString())
                .claim("uid", userId)
                .claim("role", role.name())
                .claim("cid", customerAccountId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

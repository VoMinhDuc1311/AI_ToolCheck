package com.aitoolcheck.ai_toolcheck1_backend.security;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AuthProperties;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String TOKEN_TYPE_CLAIM = "typ";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String EMAIL_CLAIM = "email";
    private static final String ROLE_CLAIM = "role";
    private static final int MIN_SECRET_BYTES = 32;

    private final AuthProperties authProperties;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        this.signingKey = buildSigningKey();
    }

    public String generateAccessToken(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(authProperties.getAccessTokenMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(EMAIL_CLAIM, user.getEmail())
                .claim(ROLE_CLAIM, user.getRole().name())
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public boolean isAccessTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            return ACCESS_TOKEN_TYPE.equals(claims.get(TOKEN_TYPE_CLAIM, String.class));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(extractClaims(token).getSubject());
    }

    public String extractEmail(String token) {
        return extractClaims(token).get(EMAIL_CLAIM, String.class);
    }

    public UserRole extractRole(String token) {
        return UserRole.valueOf(extractClaims(token).get(ROLE_CLAIM, String.class));
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey buildSigningKey() {
        String jwtSecret = authProperties.getJwtSecret();

        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("app.auth.jwt-secret must be configured");
        }

        byte[] secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.auth.jwt-secret must be at least 32 bytes");
        }

        return Keys.hmacShaKeyFor(secretBytes);
    }
}

package com.nforce.onehr.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
@Slf4j
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-hours:8}")
    private int jwtExpirationHours;

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String email, boolean mustChangePassword, int tokenVersion) {
        long nowMs = System.currentTimeMillis();
        Date expiry = new Date(nowMs + (long) jwtExpirationHours * 3_600_000);

        return Jwts.builder()
                .subject(email)
                .claim("mcp", mustChangePassword)
                .claim("tv", tokenVersion)
                .issuedAt(new Date(nowMs))
                .expiration(expiry)
                .signWith(signingKey())
                .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean extractMustChangePassword(String token) {
        Object mcp = parseClaims(token).get("mcp");
        return Boolean.TRUE.equals(mcp);
    }

    // Absent only for tokens minted before this claim existed — treated as version 0, which
    // matches every user's tokenVersion until their first role change, so pre-existing sessions
    // aren't broken by this rollout.
    public int extractTokenVersion(String token) {
        Object tv = parseClaims(token).get("tv");
        return tv == null ? 0 : ((Number) tv).intValue();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired");
        } catch (JwtException e) {
            log.debug("JWT invalid: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.debug("JWT empty");
        }
        return false;
    }

    // Only meaningful after validateToken(token) has already returned false — distinguishes
    // "expired" (session timeout: the 8h expiry in generateToken elapsed) from every other
    // rejection reason (malformed, unsigned, bad signature). JwtAuthenticationFilter uses this
    // to flag session-timeout 403s so the frontend can show a message specific to that case
    // instead of the generic "sign in again" one used for password/role invalidation.
    public boolean isExpired(String token) {
        try {
            parseClaims(token);
            return false;
        } catch (ExpiredJwtException e) {
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

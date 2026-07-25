package com.nforce.onehr.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
@Slf4j
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-hours:8}")
    private int jwtExpirationHours;

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }

    public String generateToken(String email, boolean mustChangePassword) {
        long nowMs = System.currentTimeMillis();
        Date expiry = new Date(nowMs + (long) jwtExpirationHours * 3_600_000);

        return Jwts.builder()
                .subject(email)
                .claim("mcp", mustChangePassword)
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

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

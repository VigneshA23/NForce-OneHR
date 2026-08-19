package com.nforce.onehr.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit test (no Spring context) — @Value fields are set via ReflectionTestUtils, same
 * pattern as PenaltyEvaluationSchedulerTest. Focused on the "tv" (tokenVersion) claim added for
 * server-initiated logout on role change; existing subject/mcp/expiry behavior is exercised only
 * incidentally via the same calls.
 */
class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", "test-secret-key-must-be-long-enough-for-hmac-sha-256");
        ReflectionTestUtils.setField(provider, "jwtExpirationHours", 8);
    }

    @Test
    void generateToken_roundTripsTokenVersionClaim() {
        String token = provider.generateToken("user@test.com", false, 5);

        assertEquals("user@test.com", provider.extractEmail(token));
        assertEquals(5, provider.extractTokenVersion(token));
        assertTrue(provider.validateToken(token));
    }

    @Test
    void generateToken_defaultTokenVersionZero_roundTrips() {
        String token = provider.generateToken("user@test.com", false, 0);

        assertEquals(0, provider.extractTokenVersion(token));
    }

    @Test
    void extractTokenVersion_differsFromMustChangePasswordClaim_independently() {
        String token = provider.generateToken("user@test.com", true, 7);

        assertTrue(provider.extractMustChangePassword(token));
        assertEquals(7, provider.extractTokenVersion(token));
    }
}

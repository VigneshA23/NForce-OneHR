package com.nforce.onehr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Exercises the actual server-side enforcement the password-change fix (ONEHR-179) depends on:
 * a token minted with a stale "tv" claim must fail here, while one minted with the current
 * tokenVersion must pass. Uses a real JwtTokenProvider (same ReflectionTestUtils pattern as
 * JwtTokenProviderTest) so the tokens are genuine, signed JWTs, not mocks.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String EMAIL = "user@test.com";

    @Mock private CustomUserDetailsService userDetailsService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private JwtTokenProvider jwtTokenProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret", "test-secret-key-must-be-long-enough-for-hmac-sha-256");
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpirationHours", 8);

        filter = new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AppUserPrincipal principalWithVersion(int tokenVersion) {
        return new AppUserPrincipal(UUID.randomUUID(), EMAIL, "hash", tokenVersion, "PROFILE_UPDATED", false,
                Collections.emptyList());
    }

    @Test
    void staleTokenVersion_afterPasswordChange_isRejected() throws Exception {
        // Simulates Browser B: token minted before the password change (tv=5), while the DB
        // row now sits at tv=6 (the version AuthService#changePassword bumped it to).
        String oldToken = jwtTokenProvider.generateToken(EMAIL, false, 5);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + oldToken);
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(principalWithVersion(6));

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void currentTokenVersion_afterPasswordChange_remainsValid() throws Exception {
        // Simulates Browser A: the token AuthService#changePassword minted with the
        // post-increment version — must keep working for the session that changed the password.
        String newToken = jwtTokenProvider.generateToken(EMAIL, false, 6);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + newToken);
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(principalWithVersion(6));

        filter.doFilterInternal(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(EMAIL, SecurityContextHolder.getContext().getAuthentication().getName());
        verify(filterChain).doFilter(request, response);
    }
}

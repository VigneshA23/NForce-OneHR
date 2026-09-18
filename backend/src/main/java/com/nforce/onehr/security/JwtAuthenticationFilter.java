package com.nforce.onehr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;

    // Set on the response whenever the anyRequest().authenticated() check further down the chain
    // is about to reject this request with an empty-bodied 403 (SecurityContext left unset
    // below) — the only way that response tells authFetch.ts (frontend) *why*, so it can show a
    // message specific to the cause instead of one generic "sign in again" text for all of them.
    // Values: EXPIRED, DEACTIVATED, PASSWORD_CHANGED, PROFILE_UPDATED, or (tokenVersion stale but
    // no reason recorded — legacy rows, or a bump path that doesn't set one) SESSION_INVALIDATED.
    private static final String SESSION_REASON_HEADER = "X-Session-Reason";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (token != null) {
            if (jwtTokenProvider.validateToken(token)) {
                String email = jwtTokenProvider.extractEmail(token);

                // Re-validate user against DB on every request — catches deactivated accounts immediately
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                // A password change or Super Admin profile change bumps User.tokenVersion (see
                // AuthService#changePassword/forgotPassword, UserManagementService#resetPassword/
                // updateUser); any token minted before that bump carries the old "tv" claim and
                // fails this check — same immediate-rejection effect as the isEnabled() check
                // below, but for a stale password/profile instead of deactivation.
                boolean tokenVersionCurrent = !(userDetails instanceof AppUserPrincipal principal)
                        || principal.getTokenVersion() == jwtTokenProvider.extractTokenVersion(token);

                if (userDetails.isEnabled() && tokenVersionCurrent) {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else if (!tokenVersionCurrent) {
                    log.debug("Rejected request: token version stale for {}", email);
                    String reason = (userDetails instanceof AppUserPrincipal appPrincipal
                            && appPrincipal.getTokenVersionReason() != null)
                            ? appPrincipal.getTokenVersionReason()
                            : "SESSION_INVALIDATED";
                    response.setHeader(SESSION_REASON_HEADER, reason);
                } else {
                    log.debug("Rejected request: user {} is deactivated", email);
                    response.setHeader(SESSION_REASON_HEADER, "DEACTIVATED");
                }
            } else if (jwtTokenProvider.isExpired(token)) {
                response.setHeader(SESSION_REASON_HEADER, "EXPIRED");
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

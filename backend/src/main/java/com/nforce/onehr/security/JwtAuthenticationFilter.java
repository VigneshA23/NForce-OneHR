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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (token != null && jwtTokenProvider.validateToken(token)) {
            String email = jwtTokenProvider.extractEmail(token);

            // Re-validate user against DB on every request — catches deactivated accounts immediately
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            // A Super Admin profile change bumps User.tokenVersion (see
            // UserManagementService#updateUser); any token minted before that bump carries the
            // old "tv" claim and fails this check — same immediate-rejection effect as the
            // isEnabled() check below, but for stale profile/role data instead of deactivation.
            boolean tokenVersionCurrent = !(userDetails instanceof AppUserPrincipal principal)
                    || principal.getTokenVersion() == jwtTokenProvider.extractTokenVersion(token);

            if (userDetails.isEnabled() && tokenVersionCurrent) {
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else if (!tokenVersionCurrent) {
                log.debug("Rejected request: token version stale for {}", email);
            } else {
                log.debug("Rejected request: user {} is deactivated", email);
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

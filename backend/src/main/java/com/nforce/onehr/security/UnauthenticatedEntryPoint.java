package com.nforce.onehr.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * No (or an invalid) credential reaches a protected endpoint → HTTP 401, with an empty body.
 * Spring's default answered 403, which made "you are not signed in" and "you are signed in but not
 * allowed" the same status. An authorization failure (a signed-in user without the permission) is still
 * 403 with a JSON body from GlobalExceptionHandler. JwtAuthenticationFilter's X-Session-Reason header
 * (EXPIRED, DEACTIVATED, …) was set on this same response earlier in the chain and is left untouched.
 */
@Component
public class UnauthenticatedEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}

package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnauthenticatedEntryPointTest {

    @Test
    void anUnauthenticatedRequest_gets401_withAnEmptyBody_andKeepsTheSessionReasonHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Session-Reason", "EXPIRED"); // set earlier by JwtAuthenticationFilter

        new UnauthenticatedEntryPoint().commence(new MockHttpServletRequest("GET", "/api/employees"), response,
                new InsufficientAuthenticationException("no credentials"));

        assertEquals(401, response.getStatus());
        assertEquals("", response.getContentAsString());
        assertEquals("EXPIRED", response.getHeader("X-Session-Reason"));
    }
}

package com.nforce.onehr.security;

import com.nforce.onehr.controller.RoleController;
import com.nforce.onehr.exception.GlobalExceptionHandler;
import com.nforce.onehr.service.ConvertedManagerReviewService;
import com.nforce.onehr.service.RoleAdminService;
import com.nforce.onehr.service.UserManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Roles & Permissions admin tools (role history, "who has this permission", bulk membership) are
 * gated by their own permissions — proven over MockMvc through real method security, so an
 * unauthorized caller gets HTTP 403 rather than relying on the UI hiding the buttons.
 */
@SpringJUnitConfig(RoleToolsEndpointsSecurityTest.Config.class)
class RoleToolsEndpointsSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean RoleController roleController() {
            return new RoleController(mock(RoleAdminService.class), mock(UserManagementService.class),
                    mock(ConvertedManagerReviewService.class));
        }
    }

    @Autowired RoleController roleController;

    private static final String USERS = "{\"userIds\":[\"11111111-1111-1111-1111-111111111111\"],\"remove\":false}";

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("GET", "/api/roles/5/history", null, "roles:VIEW:ALL"),
                Arguments.of("GET", "/api/permissions/holders?module=employees&action=VIEW", null, "roles:VIEW:ALL"),
                Arguments.of("POST", "/api/roles/5/members/bulk", USERS, "user_roles:EDIT:ALL"),
                // Phase 3: review of Managers whose role was converted from automatic to explicit.
                Arguments.of("GET", "/api/roles/converted-managers", null, "roles:VIEW:ALL"),
                Arguments.of("POST", "/api/roles/converted-managers/11111111-1111-1111-1111-111111111111/reviewed", null, "user_roles:EDIT:ALL"),
                Arguments.of("GET", "/api/permissions/catalogue/tree", null, "roles:VIEW:ALL"));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private int call(String method, String path, String body, String... authorities) throws Exception {
        Authentication auth = new TestingAuthenticationToken("u@test.com", "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(roleController).setControllerAdvice(new GlobalExceptionHandler()).build();
        MockHttpServletRequestBuilder req = "GET".equals(method) ? get(path) : post(path);
        req.principal(auth);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    @ParameterizedTest(name = "{0} {1} needs {3}")
    @MethodSource("endpoints")
    void withoutThePermission_isForbidden(String method, String path, String body, String authority) throws Exception {
        assertEquals(403, call(method, path, body));
        // An unrelated permission — e.g. a role that can only configure payroll — does not help.
        assertEquals(403, call(method, path, body, "payroll:CONFIGURE:ALL"));
    }

    @ParameterizedTest(name = "{0} {1} allows {3}")
    @MethodSource("endpoints")
    void withThePermission_isAllowed(String method, String path, String body, String authority) throws Exception {
        assertEquals(200, call(method, path, body, authority));
    }
}

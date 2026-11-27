package com.nforce.onehr.security;

import com.nforce.onehr.controller.LeaveConfigController;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.exception.GlobalExceptionHandler;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.LeaveTypeService;
import com.nforce.onehr.service.LeaveYearEndService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Leave Configuration (V310) through real Spring method security over MockMvc: every endpoint needs
 * its leave_configuration grant and returns 403 without it — including for someone holding every
 * leave application/approval permission, which must never be enough to configure leave.
 */
@SpringJUnitConfig(LeaveConfigEndpointsSecurityTest.Config.class)
class LeaveConfigEndpointsSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean LeaveConfigController leaveConfigController() {
            UserRepository users = mock(UserRepository.class);
            when(users.findByEmail(any())).thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).email("user@test.com").build()));
            return new LeaveConfigController(mock(LeaveTypeService.class), mock(LeaveYearEndService.class), mock(com.nforce.onehr.service.LeaveAccrualService.class), users,
                    mock(com.nforce.onehr.service.LeaveJobMonitor.class), mock(com.nforce.onehr.service.LeaveOpeningBalanceService.class));
        }

        @Bean com.nforce.onehr.controller.LeavePlanController leavePlanController() {
            return new com.nforce.onehr.controller.LeavePlanController(mock(com.nforce.onehr.service.LeavePlanService.class),
                    mock(com.nforce.onehr.service.LeavePlanAllocationService.class));
        }
    }

    @Autowired LeaveConfigController controller;
    @Autowired com.nforce.onehr.controller.LeavePlanController planController;

    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String VIEW = "leave_configuration:VIEW:ALL";
    private static final String CONFIGURE = "leave_configuration:CONFIGURE:ALL";
    private static final String EDIT = "leave_configuration:EDIT:ALL";

    static Stream<Arguments> gatedEndpoints() {
        return Stream.of(
                Arguments.of("GET", "/api/leave-config/types", null, VIEW),
                Arguments.of("POST", "/api/leave-config/types", "{\"code\":\"STUDY\",\"name\":\"Study\",\"classification\":\"PAID\"}", CONFIGURE),
                Arguments.of("PATCH", "/api/leave-config/types/" + ID, "{\"name\":\"Study Leave\"}", CONFIGURE),
                Arguments.of("PATCH", "/api/leave-config/types/" + ID + "/active", "{\"active\":false}", CONFIGURE),
                Arguments.of("DELETE", "/api/leave-config/types/" + ID, null, CONFIGURE),
                Arguments.of("POST", "/api/leave-config/types/eligibility-preview", "{}", VIEW),
                // LV-01 leave plans
                Arguments.of("GET", "/api/leave-config/plans", null, VIEW),
                Arguments.of("GET", "/api/leave-config/plans/" + ID, null, VIEW),
                Arguments.of("GET", "/api/leave-config/plans/" + ID + "/current-version", null, VIEW),
                Arguments.of("GET", "/api/leave-config/plan-versions/" + ID, null, VIEW),
                Arguments.of("POST", "/api/leave-config/plans", "{\"name\":\"Interns\",\"rules\":[]}", CONFIGURE),
                Arguments.of("PATCH", "/api/leave-config/plans/" + ID, "{\"name\":\"Interns 2\"}", CONFIGURE),
                Arguments.of("POST", "/api/leave-config/plans/" + ID + "/versions", "{\"effectiveFrom\":\"2027-01-01\",\"rules\":[]}", CONFIGURE),
                Arguments.of("POST", "/api/leave-config/plans/" + ID + "/clone", "{\"name\":\"Copy\"}", CONFIGURE),
                Arguments.of("PATCH", "/api/leave-config/plans/" + ID + "/active", "{\"active\":false}", CONFIGURE),
                Arguments.of("PATCH", "/api/leave-config/plans/" + ID + "/default", null, CONFIGURE),
                Arguments.of("DELETE", "/api/leave-config/plans/" + ID, null, CONFIGURE),
                Arguments.of("GET", "/api/leave-config/plan-allocations/employees", null, VIEW),
                Arguments.of("GET", "/api/leave-config/plan-allocations/employees/" + ID, null, VIEW),
                Arguments.of("POST", "/api/leave-config/plan-allocations", "{\"employeeUserId\":\"" + ID + "\",\"planId\":\"" + ID + "\",\"effectiveFrom\":\"2027-01-01\"}", CONFIGURE),
                Arguments.of("PUT", "/api/leave-config/plan-allocations/" + ID, "{\"planId\":\"" + ID + "\",\"effectiveFrom\":\"2027-01-01\"}", CONFIGURE),
                Arguments.of("DELETE", "/api/leave-config/plan-allocations/" + ID, null, CONFIGURE),
                Arguments.of("POST", "/api/leave-config/plan-allocations/bulk", "{\"employeeUserIds\":[\"" + ID + "\"],\"planId\":\"" + ID + "\",\"effectiveFrom\":\"2027-01-01\"}", CONFIGURE),
                Arguments.of("POST", "/api/leave-config/plan-allocations/bulk-remove", "{\"employeeUserIds\":[\"" + ID + "\"]}", CONFIGURE),
                Arguments.of("POST", "/api/leave-config/plan-allocations/check-conflicts", "{\"employeeUserIds\":[\"" + ID + "\"],\"effectiveFrom\":\"2027-01-01\"}", VIEW),
                Arguments.of("GET", "/api/leave-config/carry-forward/transfers?fromYear=2026", null, VIEW),
                Arguments.of("POST", "/api/leave-config/carry-forward/run?fromYear=2025", null, CONFIGURE),
                Arguments.of("GET", "/api/leave-config/jobs", null, VIEW),
                Arguments.of("GET", "/api/leave-config/opening-balances?year=2027", null, EDIT),
                Arguments.of("POST", "/api/leave-config/opening-balances/validate?year=2027", "[]", EDIT),
                Arguments.of("POST", "/api/leave-config/opening-balances/apply?year=2027", "[]", EDIT),
                Arguments.of("GET", "/api/leave-config/employees", null, VIEW),
                Arguments.of("GET", "/api/leave-config/employees/" + ID + "/balances", null, VIEW),
                Arguments.of("PATCH", "/api/leave-config/balances/" + ID, "{\"entitlementDays\":15,\"carriedForwardDays\":0,\"reason\":\"Correction\"}", EDIT));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0} {1} needs {3}")
    @MethodSource("gatedEndpoints")
    void withoutThePermission_isForbidden_evenWithEveryLeaveApprovalPermission(String method, String path, String body, String authority)
            throws Exception {
        assertEquals(403, call(method, path, body));
        assertEquals(403, call(method, path, body, "leave:VIEW:ALL", "leave:APPROVE:ALL"));
    }

    @ParameterizedTest(name = "{0} {1} allows {3}")
    @MethodSource("gatedEndpoints")
    void withThePermission_isAllowed(String method, String path, String body, String authority) throws Exception {
        int status = call(method, path, body, authority);
        assertTrue(status == 200 || status == 201 || status == 204, method + " " + path + " -> " + status);
    }

    @Test
    void viewAloneCannotChangeConfiguration_orAdjustBalances() throws Exception {
        assertEquals(403, call("POST", "/api/leave-config/carry-forward/run?fromYear=2025", null, VIEW));
        assertEquals(403, call("PATCH", "/api/leave-config/plans/" + ID + "/default", null, VIEW, EDIT));
        assertEquals(403, call("PATCH", "/api/leave-config/balances/" + ID,
                "{\"entitlementDays\":15,\"carriedForwardDays\":0,\"reason\":\"x\"}", VIEW, CONFIGURE));
    }

    @Test
    void seededDefaults_superAdminAndHrAdminHoldAllThree_managersAndEmployeesNone() {
        EffectivePermissionService seed = SeedMatrix.permissionService();
        for (String role : List.of("SUPER_ADMIN", "HR_ADMIN")) {
            Set<String> c = seed.coverage(List.of(SeedMatrix.role(role)));
            assertTrue(c.containsAll(List.of(VIEW, CONFIGURE, EDIT)), role);
        }
        for (String role : List.of("MANAGER", "EMPLOYEE")) {
            Set<String> c = seed.coverage(List.of(SeedMatrix.role(role)));
            assertFalse(c.contains(VIEW) || c.contains(CONFIGURE) || c.contains(EDIT), role);
        }
        // The retired leave:CONFIGURE is gone from every seeded role.
        for (String role : List.of("SUPER_ADMIN", "HR_ADMIN", "MANAGER", "EMPLOYEE")) {
            assertFalse(seed.coverage(List.of(SeedMatrix.role(role))).contains("leave:CONFIGURE:ALL"), role);
        }
    }

    @Test
    void aCustomRole_getsLeaveConfigurationOnlyWhenGranted_andItDoesNotExposeTheLeaveModule() {
        EffectivePermissionService custom = SeedMatrix.permissionService(Map.of(
                "CUSTOM_LEAVE_CONFIG", Map.of("leave_configuration:VIEW", "ALL", "leave_configuration:CONFIGURE", "ALL")));
        Role role = Role.builder().id(92).code("CUSTOM_LEAVE_CONFIG").displayName("Leave Config").build();
        Set<String> c = custom.coverage(Set.of(role));
        assertTrue(c.contains(VIEW) && c.contains(CONFIGURE));
        assertFalse(c.contains(EDIT));
        assertFalse(c.contains("leave:APPROVE:TEAM") || c.contains("leave:VIEW:TEAM"));
    }

    private int call(String method, String path, String body, String... authorities) throws Exception {
        Authentication auth = new TestingAuthenticationToken("user@test.com", "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller, planController).setControllerAdvice(new GlobalExceptionHandler()).build();
        MockHttpServletRequestBuilder req = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "PATCH" -> patch(path);
            default -> delete(path);
        };
        req.principal(auth);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }
}

package com.nforce.onehr.security;

import com.nforce.onehr.controller.AttendanceRequestController;
import com.nforce.onehr.controller.EmployeeController;
import com.nforce.onehr.controller.ExpenseController;
import com.nforce.onehr.controller.LeaveController;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.exception.GlobalExceptionHandler;
import com.nforce.onehr.service.AttendanceRequestService;
import com.nforce.onehr.service.EmployeeService;
import com.nforce.onehr.service.ExpenseService;
import com.nforce.onehr.service.LeaveService;
import com.nforce.onehr.service.LeaveTypeService;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Roles & Permissions hardening, release R1: a reporting relationship alone must never authorize
 * team approvals or team data. This drives the REAL controller beans through Spring method security
 * (the proxies carry the {@code @PreAuthorize} annotations) over MockMvc, so an unauthorized call is
 * proven to come back as HTTP 403 - unlike the plain-Mockito controller tests, which never execute
 * {@code @PreAuthorize}. Services are mocks: only the controller-level gate is under test here (the
 * service-level permission + eligibility rules are covered in LeaveServiceTest / ExpenseServiceTest).
 */
@SpringJUnitConfig(TeamApprovalEndpointsSecurityTest.Config.class)
class TeamApprovalEndpointsSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean LeaveController leaveController() { return new LeaveController(mock(LeaveService.class), mock(LeaveTypeService.class)); }
        @Bean ExpenseController expenseController() { return new ExpenseController(mock(ExpenseService.class)); }
        @Bean EmployeeController employeeController() { return new EmployeeController(mock(EmployeeService.class)); }
        @Bean AttendanceRequestController attendanceRequestController() {
            return new AttendanceRequestController(mock(AttendanceRequestService.class));
        }
    }

    @Autowired LeaveController leaveController;
    @Autowired ExpenseController expenseController;
    @Autowired EmployeeController employeeController;
    @Autowired AttendanceRequestController attendanceRequestController;

    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String REASON = "{\"reason\":\"not now\"}";

    /** method, path, body (null for GET), authority that must be held. */
    static Stream<Arguments> gatedEndpoints() {
        return Stream.of(
                Arguments.of("leave", "GET", "/api/leave/approvals", null, "leave:APPROVE:TEAM"),
                Arguments.of("leave", "POST", "/api/leave/requests/" + ID + "/approve", "", "leave:APPROVE:TEAM"),
                Arguments.of("leave", "POST", "/api/leave/requests/" + ID + "/reject", REASON, "leave:APPROVE:TEAM"),
                Arguments.of("leave", "GET", "/api/leave/team?from=2026-01-01&to=2026-01-02", null, "leave:VIEW:TEAM"),
                Arguments.of("leave", "GET", "/api/leave/balances/team", null, "leave:VIEW:TEAM"),
                Arguments.of("expense", "GET", "/api/expenses/claims/pending-manager", null, "expenses:APPROVE:TEAM"),
                Arguments.of("expense", "POST", "/api/expenses/claims/" + ID + "/manager-approve", "", "expenses:APPROVE:TEAM"),
                Arguments.of("expense", "POST", "/api/expenses/claims/" + ID + "/manager-reject", REASON, "expenses:APPROVE:TEAM"),
                Arguments.of("expense", "GET", "/api/expenses/claims/team-all", null, "expenses:VIEW:TEAM"),
                Arguments.of("expense", "GET", "/api/expenses/tiles/manager", null, "expenses:VIEW:TEAM"),
                Arguments.of("employee", "GET", "/api/employees/my-reports", null, "employees:VIEW:TEAM"),
                Arguments.of("attendanceRequest", "GET", "/api/attendance/requests/team/wfh?from=2026-01-01&to=2026-01-02", null,
                        "attendance_requests:VIEW:TEAM"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{1} {2} needs {4}")
    @MethodSource("gatedEndpoints")
    void withoutThePermission_isForbidden_evenForSomeoneWhoIsTheReportingManager(
            String controller, String method, String path, String body, String authority) throws Exception {
        // Holds an unrelated permission only (a "Roles & Permissions"-only custom role) - being the
        // employee's reporting manager is not something the HTTP layer can see, and must not matter.
        assertEquals(403, call(controller, method, path, body, "roles:VIEW:ALL"));
        assertEquals(403, call(controller, method, path, body));
    }

    @ParameterizedTest(name = "{1} {2} allows {4}")
    @MethodSource("gatedEndpoints")
    void withThePermission_isNotForbidden(String controller, String method, String path, String body, String authority)
            throws Exception {
        assertEquals(200, call(controller, method, path, body, authority));
    }

    @ParameterizedTest
    @MethodSource("seededRoleExpectations")
    void seedMatrix_keepsEveryBuiltInRoleThatWasAllowedBefore_andExcludesPlainEmployees(String role, boolean holdsAll) {
        EffectivePermissionService seed = SeedMatrix.permissionService();
        Set<String> coverage = seed.coverage(List.of(SeedMatrix.role("EMPLOYEE"), SeedMatrix.role(role)));
        List<String> authorities = gatedEndpoints().map(a -> (String) a.get()[4]).distinct().toList();
        for (String authority : authorities) {
            assertEquals(holdsAll, coverage.contains(authority), role + " vs " + authority);
        }
    }

    static Stream<Arguments> seededRoleExpectations() {
        return Stream.of(
                Arguments.of("EMPLOYEE", false),
                Arguments.of("MANAGER", true),
                Arguments.of("HR_ADMIN", true),
                Arguments.of("SUPER_ADMIN", true));
    }

    @ParameterizedTest
    @MethodSource("gatedEndpoints")
    void aCustomRole_isEligibleOnlyWhenGrantedThePermission_noBuiltInManagerRoleNeeded(
            String controller, String method, String path, String body, String authority) {
        String[] parts = authority.split(":");
        EffectivePermissionService custom = SeedMatrix.permissionService(Map.of(
                "CUSTOM_TEAM_LEAD", Map.of(parts[0] + ":" + parts[1], parts[2]),
                "CUSTOM_ROLES_ONLY", Map.of("roles:VIEW", "ALL")));
        Role teamLead = Role.builder().id(90).code("CUSTOM_TEAM_LEAD").displayName("Team Lead").build();
        Role rolesOnly = Role.builder().id(91).code("CUSTOM_ROLES_ONLY").displayName("Roles Only").build();
        assertTrue(custom.coverage(Set.of(teamLead)).contains(authority));
        assertFalse(custom.coverage(Set.of(rolesOnly)).contains(authority));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private int call(String controller, String method, String path, String body, String... authorities) throws Exception {
        Authentication auth = new TestingAuthenticationToken("user@test.com", "n/a", authorities.length == 0
                ? new String[0] : authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        Object target = switch (controller) {
            case "leave" -> leaveController;
            case "expense" -> expenseController;
            case "employee" -> employeeController;
            default -> attendanceRequestController;
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(target).setControllerAdvice(new GlobalExceptionHandler()).build();
        MockHttpServletRequestBuilder req = "GET".equals(method) ? get(path) : post(path);
        req.principal(auth);
        if (body != null && !body.isEmpty()) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }
}

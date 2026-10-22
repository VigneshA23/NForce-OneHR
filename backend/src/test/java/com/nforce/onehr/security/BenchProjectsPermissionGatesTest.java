package com.nforce.onehr.security;

import com.nforce.onehr.controller.BenchStatusController;
import com.nforce.onehr.controller.CompensationBandController;
import com.nforce.onehr.controller.OpportunityController;
import com.nforce.onehr.controller.ProjectAllocationController;
import com.nforce.onehr.controller.ProjectController;
import com.nforce.onehr.controller.SkillController;
import com.nforce.onehr.entity.Role;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V241 parity: each Bench / Opportunities / Projects / Allocations / Skill-rating / Compensation endpoint
 * that used to be {@code hasAnyRole(...)} is now {@code hasAuthority(...)}, and against the real seed
 * the authority is held by exactly the system roles the old gate allowed — so EMPLOYEE/MANAGER/HR_ADMIN/
 * SUPER_ADMIN behave identically — while a custom role passes only when granted that permission.
 */
class BenchProjectsPermissionGatesTest {

    private static final Set<String> HR = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> MGR_HR = Set.of("MANAGER", "HR_ADMIN", "SUPER_ADMIN");
    private static final List<String> SYSTEM = List.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN");

    static Stream<Arguments> gates() {
        return Stream.of(
                // controller, method, new authority, roles the old hasAnyRole(...) allowed
                Arguments.of(BenchStatusController.class, "start", "bench:CREATE:ALL", HR),
                Arguments.of(BenchStatusController.class, "end", "bench:EDIT:ALL", HR),
                Arguments.of(BenchStatusController.class, "team", "bench:VIEW:TEAM", MGR_HR),
                Arguments.of(BenchStatusController.class, "list", "bench:VIEW:ALL", HR),
                Arguments.of(BenchStatusController.class, "allocationOptions", "bench:VIEW:ALL", HR),
                Arguments.of(BenchStatusController.class, "dashboard", "bench:VIEW:ALL", HR),
                Arguments.of(BenchStatusController.class, "cost", "bench:VIEW:ALL", HR),
                Arguments.of(BenchStatusController.class, "agingConfig", "bench:VIEW:ALL", HR),
                Arguments.of(BenchStatusController.class, "saveAgingConfig", "bench:CONFIGURE:ALL", HR),
                Arguments.of(CompensationBandController.class, "bands", "compensation:VIEW:ALL", HR),
                Arguments.of(CompensationBandController.class, "assignments", "compensation:VIEW:ALL", HR),
                Arguments.of(CompensationBandController.class, "addBand", "compensation:CREATE:ALL", HR),
                Arguments.of(CompensationBandController.class, "assign", "compensation:EDIT:ALL", HR),
                Arguments.of(OpportunityController.class, "post", "opportunities:CREATE:ALL", MGR_HR),
                Arguments.of(OpportunityController.class, "interests", "opportunities:VIEW:ALL", MGR_HR),
                Arguments.of(OpportunityController.class, "close", "opportunities:APPROVE:ALL", HR),
                Arguments.of(ProjectController.class, "list", "projects:VIEW:ALL", HR),
                Arguments.of(ProjectController.class, "create", "projects:CREATE:ALL", HR),
                Arguments.of(ProjectAllocationController.class, "allocate", "project_allocations:CREATE:ALL", HR),
                Arguments.of(ProjectAllocationController.class, "bulkAllocate", "project_allocations:CREATE:ALL", HR),
                Arguments.of(ProjectAllocationController.class, "capacity", "project_allocations:VIEW:TEAM", MGR_HR),
                Arguments.of(SkillController.class, "addSkill", "talent_skills:CONFIGURE:ALL", HR),
                Arguments.of(SkillController.class, "team", "skill_ratings:VIEW:TEAM", MGR_HR),
                Arguments.of(SkillController.class, "rateAsManager", "skill_ratings:EDIT:TEAM", MGR_HR));
    }

    @ParameterizedTest(name = "{0}.{1} -> {2}")
    @MethodSource("gates")
    void gateIsThePermission_heldByExactlyTheOldRoles_andGrantableToCustomRoles(
            Class<?> controller, String method, String authority, Set<String> oldRoles) {
        assertEquals("hasAuthority('" + authority + "')", preAuthorize(controller, method));

        EffectivePermissionService seed = SeedMatrix.permissionService();
        for (String code : SYSTEM) {
            boolean holds = seed.coverage(List.of(SeedMatrix.role("EMPLOYEE"), SeedMatrix.role(code))).contains(authority);
            assertEquals(oldRoles.contains(code), holds, code + " access to " + authority + " changed");
        }

        String[] mas = authority.split(":");
        EffectivePermissionService custom = SeedMatrix.permissionService(Map.of(
                "CUSTOM_WITH", Map.of(mas[0] + ":" + mas[1], mas[2]),
                "CUSTOM_WITHOUT", Map.of("announcements:VIEW", "ALL")));
        assertTrue(custom.coverage(Set.of(customRole("CUSTOM_WITH"))).contains(authority));
        assertFalse(custom.coverage(Set.of(SeedMatrix.role("EMPLOYEE"), customRole("CUSTOM_WITHOUT"))).contains(authority));
    }

    @ParameterizedTest
    @MethodSource("ungated")
    void selfServiceEndpointsStayUngated(Class<?> controller, String method) {
        assertNull(preAuthorize(controller, method));
    }

    static Stream<Arguments> ungated() {
        return Stream.of(
                Arguments.of(OpportunityController.class, "list"),
                Arguments.of(OpportunityController.class, "expressInterest"),
                Arguments.of(SkillController.class, "taxonomy"),
                Arguments.of(SkillController.class, "myRatings"),
                Arguments.of(SkillController.class, "rateSelf"),
                Arguments.of(SkillController.class, "removeSelf"));
    }

    private static String preAuthorize(Class<?> controller, String name) {
        assertNull(controller.getAnnotation(PreAuthorize.class), controller.getSimpleName() + " must gate per method");
        List<Method> methods = Arrays.stream(controller.getDeclaredMethods()).filter(m -> m.getName().equals(name)).toList();
        assertEquals(1, methods.size(), controller.getSimpleName() + "." + name);
        PreAuthorize p = methods.get(0).getAnnotation(PreAuthorize.class);
        return p == null ? null : p.value();
    }

    private static Role customRole(String code) {
        return Role.builder().id(99).code(code).displayName(code).build();
    }
}

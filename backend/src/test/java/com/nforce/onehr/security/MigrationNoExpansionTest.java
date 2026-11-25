package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "Migrations must not silently expand anyone's privileges." For every system role, the default
 * matrix after the Roles & Permissions enhancement migrations (V277+) must equal the matrix before
 * them, plus exactly the deliberate changes listed here. A new migration that grants anything else
 * fails this test until the change is written down (and reviewed) below.
 *
 * <p>Baseline is V276, the last migration before this work. Add each later migration's deliberate
 * delta to {@link #APPROVED}; do not edit the baseline.
 */
class MigrationNoExpansionTest {

    private static final int BASELINE = 276;

    /** role -> authority ("module:ACTION:SCOPE") that is intentionally new since the baseline. */
    private static final Map<String, Set<String>> APPROVED = Map.of(
            // V277: editing a project used to need no permission at all; now an explicit HR/SA permission.
            // V296: employee_sensitive splits the sensitive profile fields out of employees:VIEW:ALL. HR_ADMIN
            // already saw and entered them, so it gets all three actions; nothing new is reachable.
            // V310: leave_configuration takes over leave-type configuration from leave:CONFIGURE (retired, see
            // APPROVED_REMOVALS) and adds Edit = manual balance adjustment, an HR/SA-only capability.
            "HR_ADMIN", Set.of("projects:EDIT:ALL", "employee_sensitive:VIEW:ALL", "employee_sensitive:EDIT:ALL",
                    "employee_sensitive:APPROVE:ALL", "leave_configuration:VIEW:ALL", "leave_configuration:EDIT:ALL",
                    "leave_configuration:CONFIGURE:ALL"),
            // V277 (projects:EDIT), V279 (audit_security — copied from user_management:VIEW:ALL, SA-only), V296 (employee_sensitive).
            // V306: finance:CONFIGURE splits the Employee Finances console out of payroll:CONFIGURE:ALL and is copied
            // from it (only SUPER_ADMIN holds that), so nothing new is reachable.
            "SUPER_ADMIN", Set.of("projects:EDIT:ALL", "audit_security:VIEW:ALL", "employee_sensitive:VIEW:ALL",
                    "employee_sensitive:EDIT:ALL", "employee_sensitive:APPROVE:ALL", "leave_configuration:VIEW:ALL",
                    "leave_configuration:EDIT:ALL", "leave_configuration:CONFIGURE:ALL", "finance:CONFIGURE:ALL"),
            "MANAGER", Set.of(),
            "EMPLOYEE", Set.of());

    /** role -> authority deliberately retired since the baseline, each replaced by an equivalent grant above. */
    private static final Map<String, Set<String>> APPROVED_REMOVALS = Map.of(
            // V310: leave:CONFIGURE (leave types) is replaced by leave_configuration:CONFIGURE.
            "HR_ADMIN", Set.of("leave:CONFIGURE:ALL"),
            "SUPER_ADMIN", Set.of("leave:CONFIGURE:ALL"));

    @Test
    void systemRoles_gainOnlyTheApprovedPermissions_andLoseNothing() {
        Map<String, Map<String, String>> before = SeedMatrix.asOfVersion(BASELINE);
        Map<String, Map<String, String>> after = SeedMatrix.asOfVersion(Integer.MAX_VALUE);

        for (String role : List.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN")) {
            Set<String> was = authorities(before.getOrDefault(role, Map.of()));
            Set<String> is = authorities(after.getOrDefault(role, Map.of()));

            Set<String> gained = new TreeSet<>(is);
            gained.removeAll(was);
            Set<String> lost = new TreeSet<>(was);
            lost.removeAll(is);

            assertEquals(new TreeSet<>(APPROVED.getOrDefault(role, Set.of())), gained, role + " gained unapproved permissions");
            assertEquals(new TreeSet<>(APPROVED_REMOVALS.getOrDefault(role, Set.of())), lost, role + " lost unapproved permissions");
        }
    }

    private static Set<String> authorities(Map<String, String> grants) {
        Set<String> out = new TreeSet<>();
        grants.forEach((key, scope) -> out.add(key + ":" + scope));
        return out;
    }
}

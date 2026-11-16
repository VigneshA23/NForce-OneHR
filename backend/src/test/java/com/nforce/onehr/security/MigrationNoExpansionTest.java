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
            "HR_ADMIN", Set.of("projects:EDIT:ALL"),
            // V277 (projects:EDIT) and V279 (audit_security — copied from user_management:VIEW:ALL, SA-only).
            "SUPER_ADMIN", Set.of("projects:EDIT:ALL", "audit_security:VIEW:ALL"),
            "MANAGER", Set.of(),
            "EMPLOYEE", Set.of());

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
            assertEquals(Set.of(), lost, role + " lost permissions");
        }
    }

    private static Set<String> authorities(Map<String, String> grants) {
        Set<String> out = new TreeSet<>();
        grants.forEach((key, scope) -> out.add(key + ":" + scope));
        return out;
    }
}

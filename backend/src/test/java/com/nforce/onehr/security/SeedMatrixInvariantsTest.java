package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seed-level invariants the Roles & Permissions privilege ceiling depends on. If a future migration
 * seeds a grant Super Admin can't cover (e.g. MANAGER audit:VIEW:TEAM while Super Admin only holds
 * OWN), Super Admins would be blocked from managing those users — this fails first.
 */
class SeedMatrixInvariantsTest {

    @Test
    void superAdminCoversEverySeededGrantOfEveryOtherSystemRole() {
        EffectivePermissionService eps = SeedMatrix.permissionService();
        Set<String> sa = eps.coverage(List.of(SeedMatrix.role("SUPER_ADMIN")));
        for (String code : List.of("EMPLOYEE", "MANAGER", "HR_ADMIN")) {
            Set<String> other = eps.coverage(List.of(SeedMatrix.role(code)));
            assertTrue(sa.containsAll(other), code + " holds grants Super Admin doesn't: "
                    + other.stream().filter(a -> !sa.contains(a)).toList());
        }
    }

    @Test
    void seedMatchesKnownAnchors() {
        // Guards the parser itself: a few V225 rows that must come through verbatim.
        assertTrue(SeedMatrix.grants("MANAGER").get("leave:APPROVE").equals("TEAM"));
        assertTrue(SeedMatrix.grants("HR_ADMIN").get("leave:APPROVE").equals("ALL"));
        assertTrue(SeedMatrix.grants("SUPER_ADMIN").get("audit:VIEW").equals("OWN"));
        assertTrue(SeedMatrix.grants("SUPER_ADMIN").containsKey("roles:CONFIGURE"));
        assertTrue(SeedMatrix.grants("EMPLOYEE").isEmpty());
    }
}

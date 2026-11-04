package com.nforce.onehr.security;

import java.util.Map;
import java.util.Set;

/**
 * A granted scope implies capability at every scope level it organizationally contains — e.g. a
 * role granted ALL for (module, action) can also satisfy a TEAM-level check, since org-wide
 * access trivially includes any team's data. This is what makes a plain
 * {@code hasAuthority("module:ACTION:TEAM")} check correct as "at least TEAM": the authority is
 * granted not just at the exact scope a role holds, but at every scope that holder covers.
 *
 * <p>DEPARTMENT and LOCATION are deliberately NOT ordered relative to each other — they're
 * orthogonal organizational dimensions (a department can span locations; a location can span
 * departments), so neither implies the other. Both sit strictly above TEAM/OWN and strictly
 * below ALL. (Phase 1's seed data never actually grants either of these two scopes — every
 * current role uses only OWN/TEAM/ALL — so this lattice shape is forward-looking, not yet
 * exercised by real data.)
 */
public final class ScopeCoverage {

    private ScopeCoverage() {
    }

    private static final Map<String, Set<String>> COVERAGE = Map.of(
            "OWN", Set.of("OWN"),
            "TEAM", Set.of("OWN", "TEAM"),
            "DEPARTMENT", Set.of("OWN", "TEAM", "DEPARTMENT"),
            "LOCATION", Set.of("OWN", "TEAM", "LOCATION"),
            "ALL", Set.of("OWN", "TEAM", "DEPARTMENT", "LOCATION", "ALL")
    );

    /** The inverse: every granted scope that satisfies a check at {@code requiredScope}. */
    public static Set<String> grantingScopes(String requiredScope) {
        Set<String> out = new java.util.HashSet<>();
        COVERAGE.forEach((granted, covered) -> { if (covered.contains(requiredScope)) out.add(granted); });
        return out;
    }

    /** Every scope level the given granted scope satisfies, including itself. */
    public static Set<String> coverage(String grantedScope) {
        return COVERAGE.getOrDefault(grantedScope, Set.of(grantedScope));
    }
}

package com.nforce.onehr.ai;

import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authorities {@code AiAssistantService#buildContext} computes for a user holding the system
 * roles behind the given audience buckets, from the REAL seed matrix - so a test context built from
 * buckets carries the same permissions a real user of those roles would.
 */
public final class SeedAuthorities {

    private static final EffectivePermissionService SEED = SeedMatrix.permissionService();
    private static final Map<AudienceBucket, String> ROLE = Map.of(
            AudienceBucket.EMPLOYEE, "EMPLOYEE", AudienceBucket.MANAGER, "MANAGER",
            AudienceBucket.HR, "HR_ADMIN", AudienceBucket.ADMIN, "SUPER_ADMIN");

    private SeedAuthorities() {}

    public static Set<String> of(AudienceBucket... buckets) {
        return of(Arrays.asList(buckets));
    }

    public static Set<String> of(Collection<AudienceBucket> buckets) {
        return ofRoles(buckets.stream().map(ROLE::get).toArray(String[]::new));
    }

    /** Seeded coverage of these system role codes. */
    public static Set<String> ofRoles(String... codes) {
        List<Role> roles = Arrays.stream(codes).map(SeedMatrix::role).toList();
        return SEED.coverage(roles);
    }
}

package com.nforce.onehr.ai.contract;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The four audience buckets OneHR already collapses its seven Role codes into — see
 * {@code com.nforce.onehr.util.RoleUtils#audienceBuckets}, which is the authoritative producer.
 * This enum exists only to give the retrieval/indexing path a typed, closed value set for a
 * security-relevant field; it deliberately mirrors {@code RoleUtils} rather than re-deriving the
 * mapping, so there is exactly one role-to-audience hierarchy in the application.
 *
 * <p>The same four values back the {@code chk_ai_chunk_audience} CHECK constraint and
 * {@code help_content_audience.audience}.
 */
public enum AudienceBucket {
    EMPLOYEE,
    MANAGER,
    HR,
    ADMIN;

    public static Optional<AudienceBucket> fromCode(String code) {
        if (code == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(b -> b.name().equalsIgnoreCase(code.trim()))
                .findFirst();
    }

    /**
     * Bridges {@code RoleUtils.audienceBuckets(...)}'s {@code Set<String>} into typed buckets.
     * Unrecognised values are dropped rather than throwing — an unknown bucket must never widen
     * visibility, and dropping it can only ever narrow what the caller is allowed to retrieve.
     */
    public static Set<AudienceBucket> from(Collection<String> codes) {
        if (codes == null) return Set.of();
        return codes.stream()
                .map(AudienceBucket::fromCode)
                .flatMap(Optional::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static Set<String> toCodes(Collection<AudienceBucket> buckets) {
        if (buckets == null) return Set.of();
        return buckets.stream().map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

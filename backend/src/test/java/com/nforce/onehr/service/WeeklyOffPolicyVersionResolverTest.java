package com.nforce.onehr.service;

import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.entity.WeeklyOffPolicyVersion;
import com.nforce.onehr.repository.WeeklyOffPolicyVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link WeeklyOffPolicyVersionResolver}'s contract — mirrors {@link ShiftVersionResolverTest}'s
 * identical shape, one concept over (Weekly Off Policy definition versions instead of Shift
 * timing versions): {@code resolve} throws for a date the Policy has no applicable version for
 * (every Policy is expected to have at least an initial version — see OrgService
 * #createWeeklyOffPolicy and the V233 migration backfill), {@code resolveIfPresent} is the
 * non-throwing counterpart.
 */
@ExtendWith(MockitoExtension.class)
class WeeklyOffPolicyVersionResolverTest {

    @Mock private WeeklyOffPolicyVersionRepository weeklyOffPolicyVersionRepository;

    private final WeeklyOffPolicy policy = WeeklyOffPolicy.builder().id(UUID.randomUUID()).name("test").build();
    private final LocalDate day = LocalDate.of(2026, 9, 10);

    @Test
    void resolve_returnsTheVersionWhenOneExists() {
        WeeklyOffPolicyVersionResolver resolver = new WeeklyOffPolicyVersionResolver(weeklyOffPolicyVersionRepository);
        WeeklyOffPolicyVersion version = WeeklyOffPolicyVersion.builder().weeklyOffPolicy(policy).offDays("SATURDAY,SUNDAY").effectiveFrom(day).build();
        when(weeklyOffPolicyVersionRepository.findFirstByWeeklyOffPolicyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(policy.getId(), day))
                .thenReturn(Optional.of(version));

        assertEquals(version, resolver.resolve(policy, day));
    }

    @Test
    void resolve_throwsWithAClearMessage_whenNoVersionIsEffective() {
        WeeklyOffPolicyVersionResolver resolver = new WeeklyOffPolicyVersionResolver(weeklyOffPolicyVersionRepository);
        when(weeklyOffPolicyVersionRepository.findFirstByWeeklyOffPolicyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(policy.getId(), day))
                .thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resolver.resolve(policy, day));
        assertTrue(ex.getMessage().contains("has no version effective on or before " + day));
    }

    @Test
    void resolveIfPresent_returnsEmpty_ratherThanThrowing_whenNoVersionIsEffective() {
        WeeklyOffPolicyVersionResolver resolver = new WeeklyOffPolicyVersionResolver(weeklyOffPolicyVersionRepository);
        when(weeklyOffPolicyVersionRepository.findFirstByWeeklyOffPolicyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(policy.getId(), day))
                .thenReturn(Optional.empty());

        assertFalse(resolver.resolveIfPresent(policy, day).isPresent());
    }

    @Test
    void resolveHistoryBatch_groupsVersionsByPolicy_preservingAscendingOrder() {
        WeeklyOffPolicyVersionResolver resolver = new WeeklyOffPolicyVersionResolver(weeklyOffPolicyVersionRepository);
        WeeklyOffPolicy otherPolicy = WeeklyOffPolicy.builder().id(UUID.randomUUID()).name("other").build();
        WeeklyOffPolicyVersion first = WeeklyOffPolicyVersion.builder().weeklyOffPolicy(policy).offDays("SATURDAY,SUNDAY").effectiveFrom(day.minusDays(10)).build();
        WeeklyOffPolicyVersion second = WeeklyOffPolicyVersion.builder().weeklyOffPolicy(policy).offDays("FRIDAY").effectiveFrom(day).build();
        WeeklyOffPolicyVersion otherRow = WeeklyOffPolicyVersion.builder().weeklyOffPolicy(otherPolicy).offDays("SUNDAY").effectiveFrom(day).build();
        when(weeklyOffPolicyVersionRepository.findByWeeklyOffPolicyIdInOrderByWeeklyOffPolicyIdAscEffectiveFromAsc(any()))
                .thenReturn(List.of(first, second, otherRow));

        Map<UUID, List<WeeklyOffPolicyVersion>> history = resolver.resolveHistoryBatch(List.of(policy.getId(), otherPolicy.getId()));

        assertEquals(List.of(first, second), history.get(policy.getId()));
        assertEquals(List.of(otherRow), history.get(otherPolicy.getId()));
    }

    @Test
    void resolveHistoryBatch_emptyInput_returnsEmptyMap() {
        WeeklyOffPolicyVersionResolver resolver = new WeeklyOffPolicyVersionResolver(weeklyOffPolicyVersionRepository);

        assertTrue(resolver.resolveHistoryBatch(List.of()).isEmpty());
    }

    // ── governingVersion (the in-memory batch-resolution helper) ──────────────────────────────

    @Test
    void governingVersion_returnsTheLatestEntryOnOrBeforeTheDate() {
        WeeklyOffPolicyVersion older = WeeklyOffPolicyVersion.builder().weeklyOffPolicy(policy).offDays("SATURDAY,SUNDAY").effectiveFrom(day.minusDays(10)).build();
        WeeklyOffPolicyVersion newer = WeeklyOffPolicyVersion.builder().weeklyOffPolicy(policy).offDays("FRIDAY").effectiveFrom(day.minusDays(1)).build();

        Optional<WeeklyOffPolicyVersion> result = WeeklyOffPolicyVersionResolver.governingVersion(List.of(older, newer), day);

        assertEquals(Optional.of(newer), result);
    }

    @Test
    void governingVersion_boundaryDate_exactEffectiveFromMatchIsIncluded() {
        WeeklyOffPolicyVersion version = WeeklyOffPolicyVersion.builder().weeklyOffPolicy(policy).offDays("FRIDAY").effectiveFrom(day).build();

        assertEquals(Optional.of(version), WeeklyOffPolicyVersionResolver.governingVersion(List.of(version), day));
    }

    @Test
    void governingVersion_dateBeforeEarliestVersion_returnsEmpty() {
        WeeklyOffPolicyVersion futureOnly = WeeklyOffPolicyVersion.builder().weeklyOffPolicy(policy).offDays("FRIDAY").effectiveFrom(day.plusDays(1)).build();

        assertTrue(WeeklyOffPolicyVersionResolver.governingVersion(List.of(futureOnly), day).isEmpty());
    }

    @Test
    void governingVersion_emptyHistory_returnsEmpty() {
        assertTrue(WeeklyOffPolicyVersionResolver.governingVersion(List.of(), day).isEmpty());
    }
}

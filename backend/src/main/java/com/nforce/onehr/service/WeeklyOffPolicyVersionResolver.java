package com.nforce.onehr.service;

import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.entity.WeeklyOffPolicyVersion;
import com.nforce.onehr.repository.WeeklyOffPolicyVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one centralized place every date-aware consumer resolves "what did this Weekly Off Policy
 * define on date D" — mirrors {@link ShiftVersionResolver}'s identical role for a Shift's own
 * timing. Nothing outside this class (and {@code OrgService}'s own CRUD, which creates/replaces
 * version rows but never resolves them for a business decision) should read a {@link
 * WeeklyOffPolicyVersion} directly for a calculation — always go through {@link #resolve}/{@link
 * #resolveIfPresent}, keyed by the specific date in question, never by "now".
 */
@Service
@RequiredArgsConstructor
public class WeeklyOffPolicyVersionResolver {

    private final WeeklyOffPolicyVersionRepository weeklyOffPolicyVersionRepository;

    /**
     * The version governing {@code workDate}: the latest version whose {@code effectiveFrom} is
     * on or before it. Every {@link WeeklyOffPolicy} is expected to have at least one version at
     * all times (created alongside the Policy itself — see {@code OrgService#createWeeklyOffPolicy}
     * — or migrated in as Version 1 for any pre-existing row), so this only throws if that
     * invariant has somehow been violated.
     */
    public WeeklyOffPolicyVersion resolve(WeeklyOffPolicy policy, LocalDate workDate) {
        return resolveIfPresent(policy, workDate)
                .orElseThrow(() -> new IllegalStateException(
                        "Weekly Off Policy '" + policy.getName() + "' (id=" + policy.getId() + ") has no version "
                                + "effective on or before " + workDate + " — every policy is expected to have at "
                                + "least an initial version (see OrgService#createWeeklyOffPolicy and the V233 "
                                + "migration backfill)."));
    }

    /** Non-throwing counterpart to {@link #resolve} — empty when {@code workDate} predates the Policy's own earliest version. */
    public Optional<WeeklyOffPolicyVersion> resolveIfPresent(WeeklyOffPolicy policy, LocalDate workDate) {
        return weeklyOffPolicyVersionRepository
                .findFirstByWeeklyOffPolicyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(policy.getId(), workDate);
    }

    /** Convenience for a live/current-state display (e.g. the Weekly Off Policies list) — equivalent to {@code resolve(policy, LocalDate.now())}. */
    public WeeklyOffPolicyVersion resolveCurrent(WeeklyOffPolicy policy) {
        return resolve(policy, LocalDate.now());
    }

    /**
     * Batch equivalent of {@link #resolveIfPresent} for a whole set of policies at once — one
     * query total, grouped by policy id with each policy's own versions ascending by
     * effectiveFrom, ready for {@link #governingVersion} to scan in-memory per date without a
     * further query. Used by {@code WorkingDayService}'s bulk, multi-date calculations.
     */
    public Map<UUID, List<WeeklyOffPolicyVersion>> resolveHistoryBatch(Collection<UUID> policyIds) {
        if (policyIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<WeeklyOffPolicyVersion>> result = new HashMap<>();
        for (WeeklyOffPolicyVersion version : weeklyOffPolicyVersionRepository
                .findByWeeklyOffPolicyIdInOrderByWeeklyOffPolicyIdAscEffectiveFromAsc(policyIds)) {
            result.computeIfAbsent(version.getWeeklyOffPolicy().getId(), k -> new java.util.ArrayList<>()).add(version);
        }
        return result;
    }

    /**
     * In-memory equivalent of {@link #resolveIfPresent}, given a policy's own version history
     * already sorted ascending by effectiveFrom (as {@link #resolveHistoryBatch} returns) — the
     * latest entry whose effectiveFrom is on or before {@code workDate}. A plain reverse linear
     * scan is intentional: a policy's own version history is tiny (edits are rare), so this stays
     * simple rather than a binary search for a handful of entries.
     */
    public static Optional<WeeklyOffPolicyVersion> governingVersion(List<WeeklyOffPolicyVersion> historyAscending, LocalDate workDate) {
        if (historyAscending == null) {
            return Optional.empty();
        }
        for (int i = historyAscending.size() - 1; i >= 0; i--) {
            WeeklyOffPolicyVersion version = historyAscending.get(i);
            if (!version.getEffectiveFrom().isAfter(workDate)) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}

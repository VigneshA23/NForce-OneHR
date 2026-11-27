package com.nforce.onehr.service;

import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeavePlan;
import com.nforce.onehr.entity.LeavePlanVersion;
import com.nforce.onehr.entity.LeaveType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * LV-01 test wiring: one org-default leave plan held in memory, with versions starting on given dates.
 * Every employee is on it. {@link #service()} is a mocked {@link LeavePlanRuleService} reading it.
 */
final class TestLeavePlans {

    static final LocalDate BEGINNING = LocalDate.of(1900, 1, 1);

    final UUID planId = UUID.randomUUID();
    /** effective-from → leave type id → rule (each version holds the full set of its types). */
    private final TreeMap<LocalDate, Map<UUID, LeavePlanCatalog.TypeRule>> versions = new TreeMap<>();
    private final LeavePlanRuleService service = mock(LeavePlanRuleService.class);

    TestLeavePlans() {
        versions.put(BEGINNING, new HashMap<>());
        lenient().when(service.catalog(any())).thenAnswer(inv -> catalog());
        lenient().when(service.catalogForAll()).thenAnswer(inv -> catalog());
        lenient().when(service.ruleFor(any(), any(), any())).thenAnswer(inv ->
                catalog().ruleFor(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
    }

    LeavePlanRuleService service() {
        return service;
    }

    /** {@code type} in every version from the beginning, with {@code days} credited YEARLY. */
    TestLeavePlans with(LeaveType type, String days) {
        return with(type, days, LeaveEntitlementPolicy.YEARLY);
    }

    TestLeavePlans with(LeaveType type, String days, String frequency) {
        return with(type, rule(type, days == null ? null : new BigDecimal(days), frequency, false, null, null,
                LeaveType.CF_FIRST, LeaveEligibilityService.Rules.EVERYONE));
    }

    /** {@code rule} for {@code type} in every version. */
    TestLeavePlans with(LeaveType type, LeavePlanCatalog.TypeRule rule) {
        versions.values().forEach(v -> v.put(type.getId(), rule));
        return this;
    }

    /** From {@code year} on, {@code type} has {@code days}/{@code frequency} (a new version from 1 Jan, copying the rest). */
    TestLeavePlans withFrom(int year, LeaveType type, String days, String frequency) {
        LocalDate from = LocalDate.of(year, 1, 1);
        Map<UUID, LeavePlanCatalog.TypeRule> base = new HashMap<>(versions.floorEntry(from).getValue());
        versions.put(from, base);
        versions.tailMap(from, true).values().forEach(v ->
                v.put(type.getId(), rule(type, new BigDecimal(days), frequency, false, null, null, LeaveType.CF_FIRST,
                        LeaveEligibilityService.Rules.EVERYONE)));
        return this;
    }

    /** Carry-forward settings of {@code type} in every version. */
    TestLeavePlans carryForward(LeaveType type, boolean enabled, String maxDays, Integer expiryMonths, String priority) {
        versions.values().forEach(v -> {
            LeavePlanCatalog.TypeRule r = v.get(type.getId());
            v.put(type.getId(), rule(type, r != null ? r.days() : BigDecimal.ZERO, r != null ? r.creditFrequency() : LeaveEntitlementPolicy.YEARLY,
                    enabled, maxDays == null ? null : new BigDecimal(maxDays), expiryMonths, priority,
                    r != null ? r.eligibility() : LeaveEligibilityService.Rules.EVERYONE));
        });
        return this;
    }

    /** "Applies to" rules of {@code type} in every version. */
    TestLeavePlans eligibility(LeaveType type, LeaveEligibilityService.Rules rules) {
        versions.values().forEach(v -> {
            LeavePlanCatalog.TypeRule r = v.get(type.getId());
            if (r != null) {
                v.put(type.getId(), rule(type, r.days(), r.creditFrequency(), r.carryForwardEnabled(), r.carryForwardMaxDays(),
                        r.carryForwardExpiryMonths(), r.carryForwardPriority(), rules));
            }
        });
        return this;
    }

    TestLeavePlans without(LeaveType type) {
        versions.values().forEach(v -> v.remove(type.getId()));
        return this;
    }

    LeavePlanCatalog.TypeRule rule(LeaveType type, BigDecimal days, String frequency, boolean cf, BigDecimal cfMax,
                                   Integer cfExpiry, String priority, LeaveEligibilityService.Rules eligibility) {
        return new LeavePlanCatalog.TypeRule(UUID.randomUUID(), planId, null, type.getId(), type.isPaid() ? days : null,
                frequency, cf, cfMax, cfExpiry, priority, eligibility);
    }

    LeavePlanCatalog catalog() {
        LeavePlan plan = LeavePlan.builder().id(planId).name("Default").orgDefault(true).build();
        List<LeavePlanVersion> vs = new ArrayList<>();
        Map<UUID, Map<UUID, LeavePlanCatalog.TypeRule>> byVersion = new HashMap<>();
        List<LocalDate> starts = new ArrayList<>(versions.keySet());
        for (int i = 0; i < starts.size(); i++) {
            UUID id = UUID.randomUUID();
            vs.add(LeavePlanVersion.builder().id(id).planId(planId).version(i + 1).effectiveFrom(starts.get(i))
                    .effectiveTo(i + 1 < starts.size() ? starts.get(i + 1).minusDays(1) : null).build());
            byVersion.put(id, versions.get(starts.get(i)));
        }
        return new LeavePlanCatalog(Map.of(planId, plan), Map.of(planId, vs), byVersion, Map.of(), Map.of(), null);
    }
}

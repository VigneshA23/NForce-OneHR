package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One off-days configuration a {@link WeeklyOffPolicy} has ever had, effective from a given
 * calendar date — the same effective-dated model {@link ShiftVersion} already applies to a
 * Shift's own timing, applied here to a Weekly Off Policy's own definition. Immutable once
 * created — editing a policy's off days always creates a NEW version rather than mutating an
 * existing one; see {@code OrgService#updateWeeklyOffPolicy}. This is what lets {@code
 * WeeklyOffPolicyVersionResolver} — the one centralized place every date-aware consumer goes
 * through — resolve "what did this Policy define on date D" correctly and permanently,
 * independent of whatever the Policy is configured as today.
 *
 * <p>{@link WeeklyOffPolicy#getOffDays()} remains on that entity as a best-effort,
 * non-authoritative display cache (dropdowns, the Organization Masters list) — it is kept in
 * sync with the current version on every create/update, but it is never the source of truth for
 * a date-aware calculation once this table exists; every correctness-critical read resolves
 * through here instead, exactly mirroring {@code Employee.shiftId}'s identical relationship to
 * {@link EmployeeShiftAssignment}.
 *
 * <p>{@code effectiveFrom} means "the first calendar date this version governs" — a plain date,
 * matching {@link ShiftVersion#getEffectiveFrom()}'s identical convention, deliberately NOT a
 * timestamp, for the same reason (workDate-keyed resolution, not an instant).
 */
@Entity
@Table(name = "weekly_off_policy_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WeeklyOffPolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "weekly_off_policy_id", nullable = false)
    private WeeklyOffPolicy weeklyOffPolicy;

    // Comma-separated java.time.DayOfWeek names, e.g. "SATURDAY,SUNDAY" — same convention as
    // WeeklyOffPolicy.offDays before it.
    @Column(name = "off_days", nullable = false)
    private String offDays;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** This version's off days as a parsed set. */
    public Set<DayOfWeek> offDaysSet() {
        if (offDays == null || offDays.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(offDays.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toSet());
    }
}

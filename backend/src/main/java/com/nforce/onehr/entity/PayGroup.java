package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

/**
 * US-C19 — a Pay Group: a legal entity's payroll cadence (cycle start/end day, processing day,
 * frequency, currency), configured once instead of hardcoded. {@code cycleStartDay}/
 * {@code cycleEndDay} describe a recurring cross-month window (e.g. "26th to 25th") — see
 * {@link #resolveCycle(LocalDate)} for how an arbitrary date maps onto one.
 *
 * <p>Multiple pay groups are supported by this entity (per US-C19's own dev notes), though only
 * one ("Default pay group") is seeded/used today.
 */
@Entity
@Table(name = "pay_groups")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "legal_entity", length = 100)
    private String legalEntity;

    // MONTHLY only for now — see chk_pay_groups_frequency.
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String frequency = "MONTHLY";

    // ISO 4217 currency code, e.g. "INR".
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    // The day of the month a cycle starts on, e.g. 26.
    @Column(name = "cycle_start_day", nullable = false)
    private int cycleStartDay;

    // The day of the month a cycle ends on, e.g. 25 (of the following month, when
    // cycleStartDay > cycleEndDay — see #resolveCycle).
    @Column(name = "cycle_end_day", nullable = false)
    private int cycleEndDay;

    // The day of the month payroll processing begins for a just-closed cycle.
    @Column(name = "processing_day", nullable = false)
    private int processingDay;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Which pay cycle {@code referenceDate} falls into, and its length in days — US-C19's own
     * LOP/proration divisor. Clamps a configured day past a short month's actual length (e.g. the
     * 30th in February) to that month's last day, same convention as calendar arithmetic
     * elsewhere in this codebase.
     *
     * <p>Two genuinely different shapes, not one formula: a cross-month window ({@code
     * cycleStartDay > cycleEndDay}, e.g. "26th to 25th" — start and end sit in different
     * calendar months) versus a same-month window ({@code cycleStartDay <= cycleEndDay}, e.g.
     * "1st to 31st" — both sit in the same month). Only the first shape is seeded/used today,
     * but the entity accepts either via the admin API, so both need to resolve correctly.
     */
    public PayCycleWindow resolveCycle(LocalDate referenceDate) {
        if (cycleStartDay <= cycleEndDay) {
            YearMonth month = referenceDate.getDayOfMonth() < cycleStartDay
                    ? YearMonth.from(referenceDate).minusMonths(1)
                    : YearMonth.from(referenceDate);
            LocalDate start = month.atDay(Math.min(cycleStartDay, month.lengthOfMonth()));
            LocalDate end = month.atDay(Math.min(cycleEndDay, month.lengthOfMonth()));
            return new PayCycleWindow(start, end);
        }

        boolean referenceDateIsInTheEndMonth = referenceDate.getDayOfMonth() <= cycleEndDay;
        YearMonth endMonth = referenceDateIsInTheEndMonth
                ? YearMonth.from(referenceDate)
                : YearMonth.from(referenceDate).plusMonths(1);
        YearMonth startMonth = endMonth.minusMonths(1);

        LocalDate end = endMonth.atDay(Math.min(cycleEndDay, endMonth.lengthOfMonth()));
        LocalDate start = startMonth.atDay(Math.min(cycleStartDay, startMonth.lengthOfMonth()));
        return new PayCycleWindow(start, end);
    }
}

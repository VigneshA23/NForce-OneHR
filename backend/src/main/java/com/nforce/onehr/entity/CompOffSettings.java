package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** Singleton company policy (row id 1): OT and/or Comp-Off, eligibility thresholds, validity. */
@Entity
@Table(name = "comp_off_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompOffSettings {
    @Id
    private Short id;

    @Column(name = "overtime_enabled", nullable = false)
    private boolean overtimeEnabled;

    @Column(name = "comp_off_enabled", nullable = false)
    private boolean compOffEnabled;

    @Column(name = "eligible_weekly_off", nullable = false)
    private boolean eligibleWeeklyOff;

    @Column(name = "eligible_holiday", nullable = false)
    private boolean eligibleHoliday;

    @Column(name = "include_weekday_extra", nullable = false)
    private boolean includeWeekdayExtra;

    @Column(name = "min_minutes_half_day", nullable = false)
    private int minMinutesHalfDay;

    @Column(name = "min_minutes_full_day", nullable = false)
    private int minMinutesFullDay;

    @Column(name = "validity_days", nullable = false)
    private int validityDays;

    @Column(name = "require_credit_approval", nullable = false)
    private boolean requireCreditApproval;

    @Column(name = "allow_partial_day", nullable = false)
    private boolean allowPartialDay;

    @Column(name = "expiry_notice_days", nullable = false)
    private int expiryNoticeDays;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean bothEnabled() {
        return overtimeEnabled && compOffEnabled;
    }

    public static CompOffSettings defaults() {
        return CompOffSettings.builder().id((short) 1).overtimeEnabled(true).compOffEnabled(false)
                .eligibleWeeklyOff(true).eligibleHoliday(true).includeWeekdayExtra(false)
                .minMinutesHalfDay(240).minMinutesFullDay(480).validityDays(90).requireCreditApproval(false)
                .allowPartialDay(true).expiryNoticeDays(7).updatedAt(LocalDateTime.now()).build();
    }
}

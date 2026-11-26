package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** PN-01: the employee's own penalties for a date range plus the current-period summary tiles. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MyPenaltiesResponse {

    private Summary summary;
    private List<MyPenaltyResponse> rows;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Summary {
        /** MONTH or WEEK — the policy's late-arrival exemption cycle (MONTH when no policy applies). */
        private String period;
        private LocalDate periodFrom;
        private LocalDate periodTo;
        /** False when no Late Arrival rule applies to this employee — the tile then reads "no rule". */
        private boolean lateRuleEnabled;
        /** Late marks recorded in the period (uncapped). */
        private int lateMarks;
        /** Configured number of exempt late marks per period; null when the rule has none. */
        private Integer lateMarksExempt;
        /** Number of days the buffer period gives to correct an incident (0 when none configured). */
        private int bufferPeriodDays;
        private int activePenalties;
        private BigDecimal deductionDays;
        private BigDecimal leaveDays;
        private BigDecimal lopDays;
    }
}

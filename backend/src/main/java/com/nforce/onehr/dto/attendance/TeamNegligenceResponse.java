package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The three Negligence panels (ONEHR-107): Late Arrivals, Least Hours Worked, Frequent Breaks. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeamNegligenceResponse {

    private List<LateArrivalEntry> lateArrivals;
    private List<DailyCount> dailyLateCounts;
    private List<LeastHoursEntry> leastHoursWorked;
    private List<HoursBucket> hoursHistogram;
    private List<FrequentBreaksEntry> frequentBreaks;
    private List<DailyAverage> breaksTrend;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LateArrivalEntry {
        private UUID employeeUserId;
        private String fullName;
        private String designationName;
        private int lateDays;
        private int activeDays;
        private double latePct;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DailyCount {
        private LocalDate date;
        private long count;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LeastHoursEntry {
        private UUID employeeUserId;
        private String fullName;
        private String designationName;
        private double avgHoursPerDay;
        private double hoursWorked;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HoursBucket {
        private String label;
        private int count;
        private double pct;
    }

    // Employees with zero punch sessions in range are excluded entirely, never shown as 0 (AC #4).
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class FrequentBreaksEntry {
        private UUID employeeUserId;
        private String fullName;
        private String designationName;
        private double totalBreakHours;
        private int totalBreakCount;
        private double avgBreaksPerDay;
    }

    /** Avg. breaks/day across all flagged employees, per day — the Frequent Breaks trend line. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DailyAverage {
        private LocalDate date;
        private double avgBreaks;
    }
}

package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.util.UUID;

/** One employee's row in the Avg. Work Hours Leaderboard (ONEHR-106). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeamEffortEntry {

    private UUID employeeUserId;
    private String fullName;
    private String designationName;
    private double avgHoursPerDay;
    private double hoursWorked;

    // working_days_in_range * 8h — no per-employee contracted-hours field exists yet (ONEHR-106 dev notes).
    private double expectedHours;
    private int activeDays;
}

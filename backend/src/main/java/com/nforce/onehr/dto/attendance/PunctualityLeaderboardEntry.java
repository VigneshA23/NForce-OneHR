package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.util.UUID;

/** One employee's row in the On-Time Leaderboard — "on time" means attendance.status == PRESENT. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PunctualityLeaderboardEntry {

    private UUID employeeUserId;
    private String fullName;
    private String designationName;
    private int onTimeDays;
    private int expectedWorkingDays;
    private double percentage;
}

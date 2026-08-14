package com.nforce.onehr.dto.attendance;

import lombok.*;

/**
 * "Me vs My Team" attendance comparison for a selected date range. Team here means the
 * employee's own peers (siblings under their current manager) — NOT a manager's direct
 * reports and NOT the ONEHR-106/107 leaderboard, which is a separate, manager-facing concept.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceStatsResponse {

    private AttendanceStatBucket me;
    private AttendanceStatBucket team;

    /** Size of the peer group the team bucket was computed over (0 if the employee has no manager on record). */
    private int teamSize;
}

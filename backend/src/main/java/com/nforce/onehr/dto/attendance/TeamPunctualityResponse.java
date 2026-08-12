package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.util.List;

/** Team Punctuality / On-Time Leaderboard (Manager → My Team → Attendance → Efforts / Punctuality). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeamPunctualityResponse {

    @Builder.Default
    private List<PunctualityLeaderboardEntry> leaderboard = List.of();

    @Builder.Default
    private List<DailyPunctuality> daily = List.of();

    private PunctualitySummary summary;
}

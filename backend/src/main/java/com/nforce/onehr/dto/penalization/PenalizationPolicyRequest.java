package com.nforce.onehr.dto.penalization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Saves the whole Penalization Policy document — all four sections together, matching the
 * approved screenshots' single edit drawer (Basic information / No attendance / Late arrival /
 * Work hours / Missing logs tabs all belong to one save action). Always creates a new immutable
 * {@link com.nforce.onehr.entity.PenalizationPolicyVersion}; never edits one in place.
 */
@Data
public class PenalizationPolicyRequest {

    @NotNull
    @Valid
    private NoAttendanceConfigDto noAttendance;

    @NotNull
    @Valid
    private LateArrivalConfigDto lateArrival;

    @NotNull
    @Valid
    private WorkHoursShortageConfigDto workHoursShortage;

    @NotNull
    @Valid
    private MissingLogsConfigDto missingLogs;
}

package com.nforce.onehr.dto.penalization;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class PenalizationPolicyResponse {

    private UUID id;
    private UUID policyId;
    private Integer version;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;

    private BasicInfoConfigDto basicInfo;
    private NoAttendanceConfigDto noAttendance;
    private LateArrivalConfigDto lateArrival;
    private WorkHoursShortageConfigDto workHoursShortage;
    private MissingLogsConfigDto missingLogs;

    private UUID createdBy;
    private LocalDateTime createdAt;
}

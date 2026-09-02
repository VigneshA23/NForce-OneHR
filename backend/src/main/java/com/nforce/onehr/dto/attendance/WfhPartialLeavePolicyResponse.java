package com.nforce.onehr.dto.attendance;

import lombok.*;

/** Current org-wide WFH/Partial Day limits — see WfhPartialLeavePolicy. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WfhPartialLeavePolicyResponse {
    private int wfhMonthlyLimitDays;
    private int partialLeaveMonthlyLimitMinutes;
    private String updatedByName;
    private String updatedAt;
}

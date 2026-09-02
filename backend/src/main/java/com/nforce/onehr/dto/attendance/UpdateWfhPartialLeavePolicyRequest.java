package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/** Any non-negative integer is accepted for either field, including 0 (see the CHECK
 * constraints on wfh_partial_leave_policy — this mirrors them so a bad value is rejected with a
 * clear message before it ever reaches the DB). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UpdateWfhPartialLeavePolicyRequest {

    @NotNull(message = "WFH monthly limit (days) is required")
    @Min(value = 0, message = "WFH monthly limit (days) must be 0 or greater")
    private Integer wfhMonthlyLimitDays;

    @NotNull(message = "Partial Leave monthly limit (minutes) is required")
    @Min(value = 0, message = "Partial Leave monthly limit (minutes) must be 0 or greater")
    private Integer partialLeaveMonthlyLimitMinutes;
}

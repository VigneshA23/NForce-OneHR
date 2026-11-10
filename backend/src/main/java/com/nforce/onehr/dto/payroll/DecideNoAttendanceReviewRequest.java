package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** US-C23 — per-row decision for a run's NO_ATTENDANCE review item. */
@Getter
@Setter
public class DecideNoAttendanceReviewRequest {

    @NotBlank
    @Pattern(regexp = "DEDUCT_FROM_LEAVE|MARK_LOP|NO_ACTION",
            message = "action must be DEDUCT_FROM_LEAVE, MARK_LOP or NO_ACTION")
    private String action;

    /** Required when action is DEDUCT_FROM_LEAVE. Payroll-side record only — does not debit the
     * employee's actual leave balance or create a LeaveRequest. See PayrollRunReviewItem's own
     * Javadoc for why. */
    private UUID selectedLeaveTypeId;

    /** Required when action is NO_ACTION. */
    private String comment;
}

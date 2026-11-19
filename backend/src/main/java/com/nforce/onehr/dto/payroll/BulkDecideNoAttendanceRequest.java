package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/** Bulk version of {@link DecideNoAttendanceReviewRequest} — the same action/leaveType/comment
 *  applied to every listed item at once. Unlike LEAVE_APPLIED's bulk decide, there's no per-item
 *  external dependency (no penalty-cancel call) that could fail independently, so this is
 *  all-or-nothing: either every item is found on this run and decided, or the whole call fails
 *  before anything is saved — see PayrollRunReviewService#bulkDecideNoAttendance. */
@Getter
@Setter
public class BulkDecideNoAttendanceRequest {

    @NotEmpty
    private List<UUID> itemIds;

    @NotBlank
    @Pattern(regexp = "DEDUCT_FROM_LEAVE|MARK_LOP|NO_ACTION",
            message = "action must be DEDUCT_FROM_LEAVE, MARK_LOP or NO_ACTION")
    private String action;

    /** Required when action is DEDUCT_FROM_LEAVE. */
    private UUID selectedLeaveTypeId;

    /** Required when action is NO_ACTION. */
    private String comment;
}

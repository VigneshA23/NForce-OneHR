package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/** US-C22 — bulk approve/reject for a run's LEAVE_APPLIED review items. */
@Getter
@Setter
public class BulkDecideLeaveReviewRequest {

    @NotEmpty
    private List<UUID> itemIds;

    @NotBlank
    @Pattern(regexp = "APPROVED|REJECTED", message = "decision must be APPROVED or REJECTED")
    private String decision;

    /** Required when decision is REJECTED — passed straight through to a PENALTY item's
     * {@code AttendancePenaltyService#cancelBulk} reason, and recorded for a LEAVE_UNPAID item. */
    private String reason;
}

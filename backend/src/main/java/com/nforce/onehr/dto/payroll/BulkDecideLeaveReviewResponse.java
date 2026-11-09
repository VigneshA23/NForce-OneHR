package com.nforce.onehr.dto.payroll;

import lombok.*;

import java.util.List;
import java.util.UUID;

/** Per-item outcome of a bulk LEAVE_APPLIED decision — a PENALTY item's rejection going through
 * {@code AttendancePenaltyService#cancelBulk} can fail independently of the others (e.g. a pending
 * regularization exists for that date), same "one failure never affects another" shape as
 * {@code PenaltyCancelResultResponse}. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BulkDecideLeaveReviewResponse {

    private List<UUID> succeededIds;
    private List<Failure> failed;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Failure {
        private UUID itemId;
        private String reason;
    }
}

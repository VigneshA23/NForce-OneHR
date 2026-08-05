package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.util.List;
import java.util.UUID;

/** Per-item outcome of a bulk approve/reject — one request's failure never rolls back another's. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BulkRegularizationResultResponse {

    private List<UUID> succeededIds;
    private List<BulkFailureDto> failed;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class BulkFailureDto {
        private UUID id;
        private String reason;
    }
}

package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.util.List;
import java.util.UUID;

/** Per-item outcome of a bulk penalty cancellation — one penalty's failure never affects another's. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PenaltyCancelResultResponse {

    private List<UUID> succeededIds;
    private List<BulkFailureDto> failed;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class BulkFailureDto {
        private UUID id;
        private String reason;
    }
}

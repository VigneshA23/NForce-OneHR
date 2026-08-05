package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;
import java.util.UUID;

// Comment is optional here too, same reasoning as ApproveRegularizationRequest — one shared
// comment (if any) is applied to every request in the batch.
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BulkApproveRegularizationRequest {

    @NotEmpty(message = "At least one request id is required")
    private List<UUID> ids;

    private String comment;
}

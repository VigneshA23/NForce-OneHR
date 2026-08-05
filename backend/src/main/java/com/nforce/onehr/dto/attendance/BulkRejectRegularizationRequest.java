package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BulkRejectRegularizationRequest {

    @NotEmpty(message = "At least one request id is required")
    private List<UUID> ids;

    @NotBlank(message = "A comment is required when rejecting requests")
    private String comment;
}

package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PenaltyCancelRequest {

    @NotEmpty(message = "At least one penalty id is required")
    private List<UUID> penaltyIds;

    @NotBlank(message = "A reason is required to cancel a penalty")
    private String reason;
}

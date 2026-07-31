package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RejectRegularizationRequest {

    @NotBlank(message = "A comment is required when rejecting a request")
    private String comment;
}

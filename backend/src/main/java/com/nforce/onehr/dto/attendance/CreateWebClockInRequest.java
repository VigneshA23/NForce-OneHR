package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreateWebClockInRequest {

    @NotBlank(message = "A reason is required for a web clock-in")
    private String reason;
}

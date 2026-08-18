package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreateWebClockInRequest {

    @NotBlank(message = "A reason is required for a web clock-in")
    private String reason;

    // See PunchTimezoneRequest's Javadoc — same optional browser-timezone hint, just carried
    // alongside the reason since this request already has a body.
    private String timezone;
}

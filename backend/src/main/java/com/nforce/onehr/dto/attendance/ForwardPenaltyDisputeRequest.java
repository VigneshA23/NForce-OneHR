package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Manager step: forward the dispute to HR with a recommendation (UPHOLD or REVERSE). */
@Getter @Setter
public class ForwardPenaltyDisputeRequest {
    @NotBlank @Pattern(regexp = "UPHOLD|REVERSE")
    private String recommendation;
    @NotBlank @Size(max = 2000)
    private String comment;
}

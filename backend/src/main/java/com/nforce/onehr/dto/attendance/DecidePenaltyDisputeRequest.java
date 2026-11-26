package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** HR step: final decision - UPHELD (penalty stands) or REVERSED (penalty reversed). */
@Getter @Setter
public class DecidePenaltyDisputeRequest {
    @NotBlank @Pattern(regexp = "UPHELD|REVERSED")
    private String decision;
    @NotBlank @Size(max = 2000)
    private String comment;
}

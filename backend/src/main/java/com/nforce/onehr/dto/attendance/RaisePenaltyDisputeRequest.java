package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class RaisePenaltyDisputeRequest {
    @NotBlank @Size(max = 2000)
    private String reason;
    @Size(max = 2000)
    private String evidence;
}

package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectLeaveRequestRequest {
    @NotBlank
    private String reason;
}

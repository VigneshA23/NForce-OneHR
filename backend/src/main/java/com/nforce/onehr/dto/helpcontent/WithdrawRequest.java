package com.nforce.onehr.dto.helpcontent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WithdrawRequest {

    @NotBlank(message = "Withdrawal reason is required")
    private String reason;
}

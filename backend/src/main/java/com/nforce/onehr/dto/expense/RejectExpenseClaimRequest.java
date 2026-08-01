package com.nforce.onehr.dto.expense;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectExpenseClaimRequest {
    @NotBlank private String reason;
}

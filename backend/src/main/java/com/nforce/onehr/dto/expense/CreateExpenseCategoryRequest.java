package com.nforce.onehr.dto.expense;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateExpenseCategoryRequest {
    @NotBlank private String name;
    @NotNull  private BigDecimal requiresReceiptAbove;
    @DecimalMin(value = "0", message = "Daily limit cannot be negative")
    private BigDecimal dailyLimit;
    @DecimalMin(value = "0", message = "Second approval threshold cannot be negative")
    private BigDecimal secondApprovalAbove;
}

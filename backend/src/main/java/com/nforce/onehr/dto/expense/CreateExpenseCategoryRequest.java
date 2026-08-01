package com.nforce.onehr.dto.expense;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateExpenseCategoryRequest {
    @NotBlank private String name;
    @NotNull  private BigDecimal requiresReceiptAbove;
    private BigDecimal dailyLimit;
    private BigDecimal secondApprovalAbove;
}

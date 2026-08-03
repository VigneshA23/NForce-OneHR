package com.nforce.onehr.dto.expense;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data @Builder
public class ExpenseCategoryResponse {
    private Integer id;
    private String name;
    private BigDecimal requiresReceiptAbove;
    private BigDecimal dailyLimit;
    private BigDecimal secondApprovalAbove;
}

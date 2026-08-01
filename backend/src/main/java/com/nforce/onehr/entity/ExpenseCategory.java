package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "expense_categories")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExpenseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 60)
    private String name;

    @Column(name = "requires_receipt_above", nullable = false)
    @Builder.Default
    private BigDecimal requiresReceiptAbove = BigDecimal.ZERO;

    @Column(name = "daily_limit")
    private BigDecimal dailyLimit;

    @Column(name = "second_approval_above")
    private BigDecimal secondApprovalAbove;
}

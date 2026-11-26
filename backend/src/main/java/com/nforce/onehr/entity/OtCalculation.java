package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ot_calculations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OtCalculation {
    public static final String CALCULATED = "CALCULATED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "shift_name")
    private String shiftName;

    @Column(name = "scheduled_minutes", nullable = false)
    private int scheduledMinutes;

    @Column(name = "worked_minutes", nullable = false)
    private int workedMinutes;

    @Column(name = "ot_minutes", nullable = false)
    private int otMinutes;

    @Column(name = "day_type", nullable = false)
    private String dayType;

    @Column(name = "rate_rule_id")
    private UUID rateRuleId;

    @Column(nullable = false)
    private BigDecimal multiplier;

    @Column(name = "hourly_rate", nullable = false)
    private BigDecimal hourlyRate;

    @Column(name = "ot_amount", nullable = false)
    private BigDecimal otAmount;

    @Column(nullable = false)
    @Builder.Default
    private String status = CALCULATED;

    @Column(nullable = false)
    private boolean adjusted;

    @Column(name = "ot_request_id")
    private UUID otRequestId;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "payroll_run_id")
    private UUID payrollRunId;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist void onCreate() { calculatedAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}

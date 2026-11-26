package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One eligible working day per employee; records whether it is paid as OT or earned as Comp-Off. */
@Entity
@Table(name = "comp_off_claims")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompOffClaim {
    public static final String PENDING = "PENDING";
    public static final String OT = "OT";
    public static final String COMP_OFF = "COMP_OFF";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "day_type", nullable = false, length = 20)
    private String dayType;

    @Column(name = "worked_minutes", nullable = false)
    private int workedMinutes;

    @Column(name = "eligible_minutes", nullable = false)
    private int eligibleMinutes;

    @Column(name = "credit_days", nullable = false)
    private BigDecimal creditDays;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String choice = PENDING;

    @Column(name = "chosen_at")
    private LocalDateTime chosenAt;

    @Column(name = "chosen_by")
    private UUID chosenBy;

    @Column(name = "credit_id")
    private UUID creditId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

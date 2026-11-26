package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** A block of Comp-Off days with its own expiry; the unit consumed (oldest expiry first) by leave. */
@Entity
@Table(name = "comp_off_credits")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompOffCredit {
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String EARNED = "EARNED";
    public static final String MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "claim_id")
    private UUID claimId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String source = EARNED;

    @Column(name = "work_date")
    private LocalDate workDate;

    @Column(name = "credit_days", nullable = false)
    private BigDecimal creditDays;

    @Column(name = "used_days", nullable = false)
    @Builder.Default
    private BigDecimal usedDays = BigDecimal.ZERO;

    @Column(name = "deducted_days", nullable = false)
    @Builder.Default
    private BigDecimal deductedDays = BigDecimal.ZERO;

    @Column(name = "expired_days", nullable = false)
    @Builder.Default
    private BigDecimal expiredDays = BigDecimal.ZERO;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "earned_on", nullable = false)
    private LocalDate earnedOn;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    private String reason;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_comment")
    private String reviewComment;

    @Column(name = "expiry_notified", nullable = false)
    private boolean expiryNotified;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Two writers (leave approval, expiry job) can touch the same credit; fail loudly instead of double-spending.
    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Days still spendable (ignores expiry date; callers check that). */
    public BigDecimal remaining() {
        return creditDays.subtract(usedDays).subtract(deductedDays).subtract(expiredDays);
    }
}

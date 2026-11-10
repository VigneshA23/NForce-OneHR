package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C22/US-C23 (ONEHR-579) — one row needing a Payroll Admin's decision before a DRAFT run can be
 * submitted, generated fresh by {@code PayrollRunService#generateDraft} alongside that employee's
 * payslip (and wiped together with it on every regenerate — same tradeoff as payslips/totals
 * themselves: a regenerate resets review decisions, it doesn't merge around them).
 *
 * <p><b>{@link PayrollRunReviewStepType#LEAVE_APPLIED}</b> — one row per unpaid {@code
 * LeaveRequest} ({@link PayrollReviewItemSourceType#LEAVE_UNPAID}) or {@code AttendancePenalty}
 * ({@link PayrollReviewItemSourceType#PENALTY}) in the cycle, {@code sourceId} pointing at that
 * row. {@link LeaveReviewDecision#APPROVED} is a pure acknowledgement — the entry already counts
 * toward LOP today, same as before this story. {@link LeaveReviewDecision#REJECTED} behaves
 * differently by source, and this asymmetry matters:
 * <ul>
 *   <li>A rejected {@code PENALTY} item calls {@code AttendancePenaltyService#cancelBulk} on the
 *       underlying penalty — a real, durable state change (CANCELLED), so it stays excluded from
 *       LOP even across a later regenerate (the cancelled penalty simply won't match the
 *       PENDING_REVIEW query next time).</li>
 *   <li>A rejected {@code LEAVE_UNPAID} item only sets THIS row's decision — nothing on {@code
 *       LeaveRequest} itself changes (there's no "excluded from payroll" field on it, and adding
 *       one would mean duplicating payroll-only state into an unrelated HR feature). If the run is
 *       regenerated again, the same leave request reappears as a fresh PENDING item and must be
 *       rejected again to keep it out of LOP — a known, deliberate consequence of resetting review
 *       items on every regenerate, not a bug.</li>
 * </ul>
 *
 * <p><b>{@link PayrollRunReviewStepType#NO_ATTENDANCE}</b> — one row per date in that employee's
 * {@code PayrollLopCalculation#getNeedsManualReviewDates()} (dates with no attendance, no leave, no
 * penalty, where the employee still has an available paid leave balance — the existing "no balance
 * left, just auto-count it" shortcut is untouched by this story). {@code sourceId} is always null —
 * nothing was filed for that day. {@link NoAttendanceReviewDecision#DEDUCT_FROM_LEAVE} ({@code
 * selectedLeaveTypeId} required) and {@link NoAttendanceReviewDecision#NO_ACTION} ({@code comment}
 * required) are payroll-side decisions only — deliberately NOT built here: no real leave balance
 * is debited, no {@code LeaveRequest} is created. Only {@link NoAttendanceReviewDecision#MARK_LOP}
 * changes a number: it adds one day to that employee's computed LOP.
 */
@Entity
@Table(name = "payroll_run_review_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayrollRunReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "step_type", nullable = false, length = 20)
    private String stepType;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "item_date", nullable = false)
    private LocalDate itemDate;

    @Column(nullable = false)
    private BigDecimal days;

    @Column(name = "leave_type_name", length = 100)
    private String leaveTypeName;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String decision = LeaveReviewDecision.PENDING;

    @Column(name = "selected_leave_type_id")
    private UUID selectedLeaveTypeId;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

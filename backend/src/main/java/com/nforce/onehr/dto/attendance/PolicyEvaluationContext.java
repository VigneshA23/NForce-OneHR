package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Everything an {@link com.nforce.onehr.service.AttendancePolicyEngine} needs to decide one
 * employee/date/discrepancy combination. {@code discrepancyType} is one of the constants on
 * {@link com.nforce.onehr.entity.ExceptionType} — a discrepancy/anomaly classification, not a
 * penalty rule by itself.
 *
 * <p>The fields below this point are per-incident attendance <em>facts</em> — already computed
 * by {@code AttendanceService}/{@code ExceptionService}, never re-derived by the policy engine
 * itself (see {@link com.nforce.onehr.service.ConfiguredAttendancePolicyEngine}). Whoever builds
 * this context (currently: tests only — see the class javadoc on
 * {@code AttendancePenaltyEvaluationService} for why there is no production caller) is
 * responsible for sourcing them:
 * <ul>
 *   <li>{@code lateMinutes} — {@code Attendance.lateByMinutes} for this date (same value already
 *       copied onto {@code AttendanceException.minutesLate} by {@code ExceptionService}).</li>
 *   <li>{@code workedMinutes} — {@code Attendance.workedMinutes} for this date.</li>
 *   <li>{@code effectiveHoursPercent} — {@code workedMinutes} as a percentage of the employee's
 *       assigned {@code Shift} duration for this date; {@code null} when the employee has no
 *       assigned shift or no attendance row for the date.</li>
 *   <li>{@code lateArrivalCountInPeriod} / {@code missingLogCountInPeriod} — count of
 *       {@code AttendanceException} rows of the matching type for this employee within the
 *       policy's configured exemption period (the calendar month containing {@code attendanceDate}
 *       — the only cycle the approved screenshots demonstrate).</li>
 *   <li>{@code lateArrivalAlsoOccurredSameDay} / {@code workHoursShortageAlsoOccurredSameDay} —
 *       whether a sibling {@code AttendanceException} of the other type exists for the same
 *       employee+date, backing the Work Hours Shortage section's "when both occur on the same
 *       day" same-day-interaction settings.</li>
 * </ul>
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PolicyEvaluationContext {

    private UUID employeeUserId;
    private LocalDate attendanceDate;
    private String discrepancyType;

    // A pending/approved regularization for this employee+date is grounds for EXEMPT under a
    // real policy — carried here so a future policy engine doesn't need to re-query for it.
    private boolean hasPendingRegularization;
    private boolean hasApprovedRegularization;

    // ── Attendance facts — see class javadoc for exactly where each comes from ──
    private Integer lateMinutes;
    private Integer workedMinutes;
    private Double effectiveHoursPercent;
    private Integer lateArrivalCountInPeriod;
    private Integer missingLogCountInPeriod;
    private boolean lateArrivalAlsoOccurredSameDay;
    private boolean workHoursShortageAlsoOccurredSameDay;
}

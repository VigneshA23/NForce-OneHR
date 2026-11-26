package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.entity.PenaltyEarlyWarning;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import com.nforce.onehr.repository.PenaltyEarlyWarningRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PN-03 - nudges an employee when they are one step away from a penalty, before any deduction
 * happens. Configured per Penalization Policy version (master switch off by default; see
 * {@code EarlyWarningConfigDto}) and run nightly right after penalty evaluation.
 *
 * <ul>
 *   <li><b>Late arrival / missing logs</b> - warns when the number of exempt occurrences left in
 *       the policy's exemption period reaches the configured remaining count (default 1, i.e.
 *       "limit minus 1"). Counted exactly the way the penalty engine counts them.</li>
 *   <li><b>Work-hours shortage</b> - for WEEK/MONTH shortage periods, warns when the period's
 *       work-hours percentage sits within the configured margin above a shortage tier, and says
 *       how many more hours are needed to stay clear of it. A per-DAY shortage period has no
 *       multi-day run-up to warn about, so it is not forewarned.</li>
 * </ul>
 *
 * <p>Every warning is de-duplicated by (employee, type, period start, threshold): the row in
 * {@code penalty_early_warnings} is inserted first and its unique constraint is the guarantee, so
 * the same threshold is never notified twice in a period even across overlapping runs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EarlyWarningService {

    static final String NOTIFICATION_TYPE = "ATTENDANCE_EARLY_WARNING";
    private static final String TITLE = "Heads-up: attendance penalty ahead";
    private static final String LINK = "/attendance?tab=penalties";

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PenalizationPolicyResolutionService policyResolutionService;
    private final AttendanceRepository attendanceRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final PenalizationPolicyWorkHoursTierRepository tierRepository;
    private final WorkHoursShortageCalculationService shortageCalculationService;
    private final PenaltyEarlyWarningRepository warningRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final AttendanceProperties attendanceProperties;
    private final PlatformTransactionManager transactionManager;

    /** Nightly entry point: one transaction per employee so one failure never blocks the rest. */
    public void runNightly() {
        LocalDate today = LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
        Set<UUID> ids = userRepository.findAttendancePolicySubjectIds();
        if (ids.isEmpty()) return;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int sent = 0;
        int failed = 0;
        for (Employee employee : employeeRepository.findAllByIdWithScheduleDetails(ids)) {
            try {
                Integer n = tx.execute(s -> evaluate(employee, today));
                sent += n != null ? n : 0;
            } catch (RuntimeException e) {
                failed++;
                log.error("Early-warning evaluation failed for employee {} - retried on the next run", employee.getUserId(), e);
            }
        }
        log.info("Early warnings: {} sent, {} employee(s) failed", sent, failed);
    }

    /** Evaluates one employee as of {@code today}; returns how many warnings were sent. */
    int evaluate(Employee employee, LocalDate today) {
        if (employee.getLastWorkingDay() != null && today.isAfter(employee.getLastWorkingDay())) return 0;
        PenalizationPolicyVersion v = policyResolutionService.resolveEffectiveVersionForEmployee(employee, today);
        if (v == null || !v.isEwEnabled()) return 0;

        int sent = 0;
        sent += lateArrivalWarning(employee, v, today);
        sent += missingLogsWarning(employee, v, today);
        sent += shortageWarning(employee, v, today);
        return sent;
    }

    // -- late arrival / missing logs: exempt count nearly used ------------------------------------

    private int lateArrivalWarning(Employee e, PenalizationPolicyVersion v, LocalDate today) {
        if (!v.isEwLateArrivalEnabled() || !v.isLateArrivalEnabled() || v.getLaExemptCount() == null
                || "TOTAL_HOURS".equals(v.getLaBasis())) return 0;
        LocalDate[] cycle = ExceptionService.cyclePeriod(today, v.getLaExemptPeriod());
        // Same live count the engine uses (Attendance.status == LATE), so a regularized day stops counting.
        int used = (int) attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(
                e.getUserId(), cycle[0], today, "LATE");
        int exempt = v.getLaExemptCount();
        int remaining = exempt - used;
        if (remaining < 1 || remaining > v.getEwExemptRemaining()) return 0;
        String what = "Your next " + (remaining == 1 ? "late arrival is" : remaining + " late arrivals are") + " still exempt";
        String message = "You have used " + used + " of " + exempt + " exempt late marks this "
                + periodWord(v.getLaExemptPeriod()) + ". " + what + "; after that, each late arrival is penalised"
                + deductionNote(v.getLaDeductionDays()) + ". Arrive within your grace time to avoid it.";
        return warn(e, PenaltyEarlyWarning.LATE_ARRIVAL, cycle[0], "REMAINING_" + remaining, message,
                "Late marks used", used + " of " + exempt, "Exempt left", String.valueOf(remaining));
    }

    private int missingLogsWarning(Employee e, PenalizationPolicyVersion v, LocalDate today) {
        if (!v.isEwMissingLogsEnabled() || !v.isMissingLogsEnabled() || v.getMlExemptDays() == null) return 0;
        LocalDate[] cycle = ExceptionService.cyclePeriod(today, v.getMlExemptPeriod());
        int used = (int) attendanceExceptionRepository.countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                e.getUserId(), ExceptionType.MISSING_PUNCH, cycle[0], today);
        int exempt = v.getMlExemptDays();
        int remaining = exempt - used;
        if (remaining < 1 || remaining > v.getEwExemptRemaining()) return 0;
        String message = "You have used " + used + " of " + exempt + " exempt missing-log days this "
                + periodWord(v.getMlExemptPeriod()) + ". " + remaining + " more " + (remaining == 1 ? "is" : "are")
                + " still exempt; after that, each missing punch is penalised" + deductionNote(v.getMlDeductionDays())
                + ". Remember to punch in and out, or regularize a missed punch promptly.";
        return warn(e, PenaltyEarlyWarning.MISSING_LOGS, cycle[0], "REMAINING_" + remaining, message,
                "Missing-log days used", used + " of " + exempt, "Exempt left", String.valueOf(remaining));
    }

    // -- work-hours shortage: period percentage close to a tier ----------------------------------

    private int shortageWarning(Employee e, PenalizationPolicyVersion v, LocalDate today) {
        if (!v.isEwShortageEnabled() || !v.isWorkHoursShortageEnabled()) return 0;
        WorkHoursShortageCalculationService.CycleHours ch = shortageCalculationService.computeCycleHours(e, today, v);
        if (ch == null || ch.expectedSoFarMinutes() <= 0 || ch.expectedCycleMinutes() <= 0) return 0;
        double percent = ch.workedMinutes() * 100.0 / ch.expectedSoFarMinutes();
        double margin = v.getEwShortageMarginPercent() != null ? v.getEwShortageMarginPercent().doubleValue() : 10;

        // The nearest tier the employee is still above - the one about to trigger. One warning per run.
        Optional<PenalizationPolicyWorkHoursTier> nearest = tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(v.getId()).stream()
                .filter(t -> percent >= t.getThresholdPercent().doubleValue()
                        && percent - t.getThresholdPercent().doubleValue() <= margin)
                .max(Comparator.comparing(PenalizationPolicyWorkHoursTier::getThresholdPercent));
        if (nearest.isEmpty()) return 0;
        PenalizationPolicyWorkHoursTier tier = nearest.get();

        double neededWorked = tier.getThresholdPercent().doubleValue() / 100.0 * ch.expectedCycleMinutes();
        long stillNeeded = (long) Math.ceil(neededWorked - ch.workedMinutes());
        if (stillNeeded <= 0) return 0; // already safe for the whole period even with no more work
        String period = "WEEK".equals(v.getWhsDeductionPeriod()) ? "week" : "month";
        String threshold = tier.getThresholdPercent().stripTrailingZeros().toPlainString();
        String message = "You have worked " + hm(ch.workedMinutes()) + " of the " + hm(ch.expectedSoFarMinutes())
                + " expected so far this " + period + " (" + Math.round(percent) + "%). Finishing the " + period
                + " below " + threshold + "% of required hours leads to a " + tier.getDeductionDays().stripTrailingZeros().toPlainString()
                + "-day deduction - you still need at least " + hm(stillNeeded) + " more before the " + period + " ends.";
        return warn(e, PenaltyEarlyWarning.WORK_HOURS_SHORTAGE, ch.periodStart(), "TIER_" + threshold, message,
                "Hours worked", hm(ch.workedMinutes()) + " of " + hm(ch.expectedSoFarMinutes()), "Hours still needed", hm(stillNeeded));
    }

    // -- sending, de-duplicated ----------------------------------------------------------------

    /** Records the warning first (the unique key is the de-dup guarantee), then notifies. 1 if sent, else 0. */
    private int warn(Employee e, String type, LocalDate periodStart, String thresholdKey, String message, String... emailRows) {
        if (warningRepository.existsByEmployeeUserIdAndWarningTypeAndPeriodStartAndThresholdKey(
                e.getUserId(), type, periodStart, thresholdKey)) {
            return 0;
        }
        try {
            warningRepository.saveAndFlush(PenaltyEarlyWarning.builder()
                    .employeeUserId(e.getUserId()).warningType(type).periodStart(periodStart).thresholdKey(thresholdKey).build());
        } catch (DataIntegrityViolationException race) {
            return 0; // another run recorded the same warning first
        }
        notificationService.send(e.getUserId(), NOTIFICATION_TYPE, TITLE, message, LINK);
        if (e.getUser() != null && e.getUser().getEmail() != null) {
            emailService.sendEarlyWarningEmail(e.getUser().getEmail(), e.getFullName(), TITLE, message, emailRows);
        }
        return 1;
    }

    private static String periodWord(String period) {
        return "WEEK".equals(period) ? "week" : "month";
    }

    private static String deductionNote(BigDecimal days) {
        return days != null && days.signum() > 0 ? " (" + days.stripTrailingZeros().toPlainString() + " day deduction)" : "";
    }

    private static String hm(long minutes) {
        return (minutes / 60) + "h " + String.format("%02d", minutes % 60) + "m";
    }
}

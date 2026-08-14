package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.dto.exceptions.ExceptionResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExceptionService {

    private static final Set<String> HR_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> PENDING_REGULARIZATION_STATUSES = Set.of("PENDING", "PARTIALLY_APPROVED");

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;
    private final AttendanceProperties attendanceProperties;
    private final EmailService emailService;
    private final AttendancePenaltyEvaluationService attendancePenaltyEvaluationService;

    /**
     * HR Admin + Super Admin see company-wide exceptions; Manager sees only current
     * direct reports (via EmployeeManagerHistory). Scope is resolved from the caller's
     * roles only — never client-supplied. HR/Super Admin takes precedence over Manager
     * for any user holding both roles.
     *
     * This dashboard is an individual-contributor view only: an exception subject must
     * hold the EMPLOYEE role and none of MANAGER/HR_ADMIN/SUPER_ADMIN (see
     * UserRepository.findEmployeeRoleUserIds()). Admin/HR/Manager accounts never appear
     * as exception subjects, company-wide or as a direct report, even if they were also
     * granted EMPLOYEE (e.g. to punch in/out themselves) or their own attendance would
     * otherwise qualify.
     */
    @Transactional
    public List<ExceptionResponse> getExceptionsForCaller(String actorEmail, LocalDate from, LocalDate to) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        Set<String> roleCodes = actor.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        Set<UUID> employeeRoleIds = userRepository.findEmployeeRoleUserIds();

        List<UUID> scopeIds;
        if (roleCodes.stream().anyMatch(HR_ROLES::contains)) {
            scopeIds = new java.util.ArrayList<>(employeeRoleIds);
        } else if (roleCodes.contains("MANAGER")) {
            scopeIds = historyRepository.findByManagerUserIdAndEffectiveToIsNull(actor.getId()).stream()
                    .map(EmployeeManagerHistory::getEmployeeUserId)
                    .filter(employeeRoleIds::contains)
                    .collect(Collectors.toList());
        } else {
            throw new AccessDeniedException("Not authorized to view exceptions");
        }

        detectExceptions(scopeIds, from, to);

        List<AttendanceException> exceptions = attendanceExceptionRepository
                .findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(scopeIds, from, to);

        return exceptions.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * Reads real attendance_records for the scope/date range and upserts all exception
     * types into attendance_exceptions:
     *  - LATE_ARRIVAL: the check-in was already flagged late by AttendanceService against
     *    the same shift-start/grace configuration — reused here rather than re-derived.
     *  - MISSING_PUNCH: a past day (never today, which may still legitimately be open) has
     *    a check-in but no check-out.
     *  - LEAVE_ATTENDANCE_CONFLICT: an approved leave request covers the same day a
     *    check-in was also recorded.
     *
     * <p>The moment a discrepancy is first detected, {@code upsertException} also evaluates it
     * against the configured Penalization Policy (see {@link #evaluatePolicy}) — this dashboard
     * load (HR/Super Admin viewing company-wide exceptions, or a Manager viewing their team's) is
     * the existing, already-invoked production trigger; no scheduler is introduced.
     */
    private void detectExceptions(Collection<UUID> scopeIds, LocalDate from, LocalDate to) {
        List<UUID> scopeIdList = new java.util.ArrayList<>(scopeIds);
        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(scopeIdList, from, to);

        LocalDate today = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();

        List<LeaveRequest> approvedLeave = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        scopeIdList, "APPROVED", to, from);

        Set<String> leaveCoveredDays = approvedLeave.stream()
                .flatMap(leave -> leave.getStartDate().datesUntil(leave.getEndDate().plusDays(1))
                        .map(date -> leave.getEmployeeUserId() + "|" + date))
                .collect(Collectors.toSet());

        for (Attendance record : records) {
            if (record.getLateByMinutes() != null && record.getLateByMinutes() > 0) {
                upsertException(record, ExceptionType.LATE_ARRIVAL,
                        attendanceProperties.getShiftStart(), record.getCheckInAt().toLocalTime(),
                        record.getLateByMinutes());
            }
            if (record.getCheckInAt() != null && record.getCheckOutAt() == null && record.getWorkDate().isBefore(today)) {
                upsertException(record, ExceptionType.MISSING_PUNCH,
                        null, record.getCheckInAt().toLocalTime(), null);
            }
            if (record.getCheckInAt() != null && leaveCoveredDays.contains(record.getEmployeeUserId() + "|" + record.getWorkDate())) {
                upsertException(record, ExceptionType.LEAVE_ATTENDANCE_CONFLICT,
                        null, record.getCheckInAt().toLocalTime(), null);
            }
        }
    }

    private void upsertException(Attendance record, String exceptionType,
                                  LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
        UUID employeeUserId = record.getEmployeeUserId();
        LocalDate exceptionDate = record.getWorkDate();
        Optional<AttendanceException> existing = attendanceExceptionRepository
                .findByEmployeeUserIdAndExceptionDateAndExceptionType(employeeUserId, exceptionDate, exceptionType);
        boolean isNew = existing.isEmpty();

        AttendanceException exception = existing.orElseGet(() -> AttendanceException.builder()
                .employeeUserId(employeeUserId)
                .exceptionDate(exceptionDate)
                .exceptionType(exceptionType)
                .build());
        exception.setExpectedTime(expectedTime);
        exception.setActualTime(actualTime);
        exception.setMinutesLate(minutesLate);
        attendanceExceptionRepository.save(exception);

        // Email once, and evaluate the configured Penalization Policy once, the moment an
        // exception is first detected — never on later re-detection of the same row (every
        // dashboard load re-runs detectExceptions). AttendancePenaltyEvaluationService has its
        // own defensive duplicate guard regardless (see its class javadoc).
        if (isNew) {
            evaluatePolicy(record, exceptionType);
            notifyEmployee(employeeUserId, exceptionType, exceptionDate, expectedTime, actualTime, minutesLate);
        }
    }

    private void notifyEmployee(UUID employeeUserId, String exceptionType, LocalDate exceptionDate,
                                 LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
        employeeRepository.findById(employeeUserId).ifPresent(employee -> {
            String email = employee.getUser().getEmail();
            String name = employee.getFullName();
            String managerEmail = currentManagerEmail(employeeUserId);
            if (ExceptionType.LATE_ARRIVAL.equals(exceptionType)) {
                emailService.sendLateArrivalEmail(email, managerEmail, name, exceptionDate, expectedTime, actualTime, minutesLate);
            } else if (ExceptionType.MISSING_PUNCH.equals(exceptionType)) {
                emailService.sendMissingPunchEmail(email, managerEmail, name, exceptionDate, actualTime);
            } else if (ExceptionType.LEAVE_ATTENDANCE_CONFLICT.equals(exceptionType)) {
                emailService.sendLeaveAttendanceConflictEmail(email, managerEmail, name, exceptionDate, actualTime);
            }
        });
    }

    /**
     * The one production entry point into the Penalization Policy seam: builds a
     * {@link PolicyEvaluationContext} entirely from facts {@code AttendanceService} already
     * computed (never re-derived here) and hands it to
     * {@link AttendancePenaltyEvaluationService#evaluate}, which calls the configured
     * {@link AttendancePolicyEngine} and persists an {@link AttendancePenalty} only on
     * {@code APPLY_PENALTY}. Every discrepancy type reaches here uniformly — the engine itself,
     * not this method, decides which (if any) configured section applies.
     */
    private void evaluatePolicy(Attendance record, String exceptionType) {
        UUID employeeUserId = record.getEmployeeUserId();
        LocalDate exceptionDate = record.getWorkDate();

        List<RegularizationRequest> regularizations = regularizationRequestRepository
                .findByEmployeeUserIdInAndAttendanceDateBetween(List.of(employeeUserId), exceptionDate, exceptionDate);
        boolean hasPending = regularizations.stream().anyMatch(r -> PENDING_REGULARIZATION_STATUSES.contains(r.getStatus()));
        boolean hasApproved = regularizations.stream().anyMatch(r -> "APPROVED".equals(r.getStatus()));

        LocalDate periodStart = exceptionDate.withDayOfMonth(1);
        LocalDate periodEnd = exceptionDate.withDayOfMonth(exceptionDate.lengthOfMonth());
        int lateArrivalCount = (int) attendanceExceptionRepository.countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                employeeUserId, ExceptionType.LATE_ARRIVAL, periodStart, periodEnd);
        int missingLogCount = (int) attendanceExceptionRepository.countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                employeeUserId, ExceptionType.MISSING_PUNCH, periodStart, periodEnd);

        boolean lateArrivalSameDay = !ExceptionType.LATE_ARRIVAL.equals(exceptionType)
                && attendanceExceptionRepository.existsByEmployeeUserIdAndExceptionDateAndExceptionType(
                        employeeUserId, exceptionDate, ExceptionType.LATE_ARRIVAL);
        boolean workHoursShortageSameDay = !ExceptionType.WORK_HOURS_SHORTAGE.equals(exceptionType)
                && attendanceExceptionRepository.existsByEmployeeUserIdAndExceptionDateAndExceptionType(
                        employeeUserId, exceptionDate, ExceptionType.WORK_HOURS_SHORTAGE);

        PolicyEvaluationContext context = PolicyEvaluationContext.builder()
                .employeeUserId(employeeUserId)
                .attendanceDate(exceptionDate)
                .discrepancyType(exceptionType)
                .hasPendingRegularization(hasPending)
                .hasApprovedRegularization(hasApproved)
                .lateMinutes(record.getLateByMinutes())
                .workedMinutes(record.getWorkedMinutes())
                .effectiveHoursPercent(computeEffectiveHoursPercent(record))
                .lateArrivalCountInPeriod(lateArrivalCount)
                .missingLogCountInPeriod(missingLogCount)
                .lateArrivalAlsoOccurredSameDay(lateArrivalSameDay)
                .workHoursShortageAlsoOccurredSameDay(workHoursShortageSameDay)
                .build();

        attendancePenaltyEvaluationService.evaluate(context);
    }

    /** {@code workedMinutes} as a percentage of the employee's assigned shift duration — null (not 0%) when either fact is unavailable, so the engine can tell "no data" apart from "worked nothing". */
    private Double computeEffectiveHoursPercent(Attendance record) {
        if (record.getWorkedMinutes() == null) {
            return null;
        }
        Employee employee = employeeRepository.findById(record.getEmployeeUserId()).orElse(null);
        if (employee == null || employee.getShift() == null) {
            return null;
        }
        long shiftMinutes = Duration.between(employee.getShift().getStartTime(), employee.getShift().getEndTime()).toMinutes();
        if (shiftMinutes <= 0) {
            return null;
        }
        return record.getWorkedMinutes() * 100.0 / shiftMinutes;
    }

    /** Null if the employee has no current manager on file — the email is simply sent without a cc. */
    private String currentManagerEmail(UUID employeeUserId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeUserId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .flatMap(userRepository::findById)
                .map(User::getEmail)
                .orElse(null);
    }

    private ExceptionResponse toResponse(AttendanceException exception) {
        Optional<Employee> employee = employeeRepository.findById(exception.getEmployeeUserId());
        return ExceptionResponse.builder()
                .id(exception.getId())
                .employeeUserId(exception.getEmployeeUserId())
                .employeeCode(employee.map(Employee::getEmployeeCode).orElse(null))
                .employeeFullName(employee.map(Employee::getFullName).orElse(null))
                .exceptionDate(exception.getExceptionDate())
                .exceptionType(exception.getExceptionType())
                .expectedTime(exception.getExpectedTime())
                .actualTime(exception.getActualTime())
                .minutesLate(exception.getMinutesLate())
                .status(exception.getStatus())
                .detectedAt(exception.getDetectedAt())
                .build();
    }
}

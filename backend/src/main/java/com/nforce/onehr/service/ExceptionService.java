package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.exceptions.ExceptionResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AttendanceProperties attendanceProperties;
    private final EmailService emailService;

    /**
     * HR Admin + Super Admin see company-wide exceptions; Manager sees only current
     * direct reports (via EmployeeManagerHistory). Scope is resolved from the caller's
     * roles only — never client-supplied. HR/Super Admin takes precedence over Manager
     * for any user holding both roles.
     *
     * This dashboard is an individual-contributor view only. "Employee" here means
     * exactly what EmployeeService.listEmployees() (the Employee Master / Employees
     * page) means: holds the EMPLOYEE role. Admin/HR/Manager-only accounts never appear
     * as exception subjects, company-wide or as a direct report, even if their own
     * attendance would otherwise qualify.
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
                upsertException(record.getEmployeeUserId(), record.getWorkDate(), ExceptionType.LATE_ARRIVAL,
                        attendanceProperties.getShiftStart(), record.getCheckInAt().toLocalTime(),
                        record.getLateByMinutes());
            }
            if (record.getCheckInAt() != null && record.getCheckOutAt() == null && record.getWorkDate().isBefore(today)) {
                upsertException(record.getEmployeeUserId(), record.getWorkDate(), ExceptionType.MISSING_PUNCH,
                        null, record.getCheckInAt().toLocalTime(), null);
            }
            if (record.getCheckInAt() != null && leaveCoveredDays.contains(record.getEmployeeUserId() + "|" + record.getWorkDate())) {
                upsertException(record.getEmployeeUserId(), record.getWorkDate(), ExceptionType.LEAVE_ATTENDANCE_CONFLICT,
                        null, record.getCheckInAt().toLocalTime(), null);
            }
        }
    }

    private void upsertException(UUID employeeUserId, LocalDate exceptionDate, String exceptionType,
                                  LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
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

        // Email once, the moment an exception is first detected — never on later
        // re-detection of the same row (every dashboard load re-runs detectExceptions).
        if (isNew) {
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

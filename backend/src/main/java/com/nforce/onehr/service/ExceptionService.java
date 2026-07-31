package com.nforce.onehr.service;

import com.nforce.onehr.dto.exceptions.ExceptionResponse;
import com.nforce.onehr.dto.exceptions.PlaceholderCheckinRequest;
import com.nforce.onehr.dto.exceptions.PlaceholderCheckinResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
    private final PlaceholderCheckinSeedRepository placeholderCheckinSeedRepository;
    private final AuditService auditService;

    /**
     * HR Admin + Super Admin see company-wide exceptions; Manager sees only current
     * direct reports (via EmployeeManagerHistory). Scope is resolved from the caller's
     * roles only — never client-supplied. HR/Super Admin takes precedence over Manager
     * for any user holding both roles.
     */
    @Transactional
    public List<ExceptionResponse> getExceptionsForCaller(String actorEmail, LocalDate from, LocalDate to) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        Set<String> roleCodes = actor.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());

        List<UUID> scopeIds; // null = company-wide
        if (roleCodes.stream().anyMatch(HR_ROLES::contains)) {
            scopeIds = null;
        } else if (roleCodes.contains("MANAGER")) {
            scopeIds = historyRepository.findByManagerUserIdAndEffectiveToIsNull(actor.getId()).stream()
                    .map(EmployeeManagerHistory::getEmployeeUserId)
                    .collect(Collectors.toList());
        } else {
            throw new AccessDeniedException("Not authorized to view exceptions");
        }

        detectLateArrivals(scopeIds, from, to);

        List<AttendanceException> exceptions = scopeIds == null
                ? attendanceExceptionRepository.findByExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(from, to)
                : attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(scopeIds, from, to);

        return exceptions.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * Reads placeholder_checkin_seed (temporary stand-in for real attendance data) and
     * upserts LATE_ARRIVAL rows into attendance_exceptions. THIS is the one method to
     * replace when FR-004 (Attendance Management) lands — swap the placeholder read for
     * real check-in/shift data; entity, repository, controller, and DTOs stay unchanged.
     */
    private void detectLateArrivals(Collection<UUID> scopeIds, LocalDate from, LocalDate to) {
        List<PlaceholderCheckinSeed> rows = scopeIds == null
                ? placeholderCheckinSeedRepository.findByWorkDateBetween(from, to)
                : placeholderCheckinSeedRepository.findByEmployeeUserIdInAndWorkDateBetween(new ArrayList<>(scopeIds), from, to);

        for (PlaceholderCheckinSeed row : rows) {
            LocalTime lateAfter = row.getShiftStartTime().plusMinutes(row.getLateThresholdMinutes());
            if (!row.getCheckinTime().isAfter(lateAfter)) {
                continue;
            }
            int minutesLate = (int) Duration.between(row.getShiftStartTime(), row.getCheckinTime()).toMinutes();
            AttendanceException exception = attendanceExceptionRepository
                    .findByEmployeeUserIdAndExceptionDateAndExceptionType(row.getEmployeeUserId(), row.getWorkDate(), ExceptionType.LATE_ARRIVAL)
                    .orElseGet(() -> AttendanceException.builder()
                            .employeeUserId(row.getEmployeeUserId())
                            .exceptionDate(row.getWorkDate())
                            .exceptionType(ExceptionType.LATE_ARRIVAL)
                            .build());
            exception.setExpectedTime(row.getShiftStartTime());
            exception.setActualTime(row.getCheckinTime());
            exception.setMinutesLate(minutesLate);
            attendanceExceptionRepository.save(exception);
        }
    }

    // TEMPORARY — delete with FR-004 (see PlaceholderCheckinSeed entity Javadoc).
    @Transactional
    public PlaceholderCheckinResponse seedPlaceholderCheckin(PlaceholderCheckinRequest req, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        if (!employeeRepository.existsById(req.getEmployeeUserId())) {
            throw new IllegalArgumentException("Employee not found");
        }

        PlaceholderCheckinSeed seed = placeholderCheckinSeedRepository
                .findByEmployeeUserIdAndWorkDate(req.getEmployeeUserId(), req.getWorkDate())
                .orElseGet(() -> PlaceholderCheckinSeed.builder()
                        .employeeUserId(req.getEmployeeUserId())
                        .workDate(req.getWorkDate())
                        .createdBy(actor.getId())
                        .build());

        seed.setCheckinTime(req.getCheckinTime());
        if (req.getShiftStartTime() != null) seed.setShiftStartTime(req.getShiftStartTime());
        if (req.getLateThresholdMinutes() != null) seed.setLateThresholdMinutes(req.getLateThresholdMinutes());

        seed = placeholderCheckinSeedRepository.save(seed);
        auditService.log(actor.getId(), "PLACEHOLDER_CHECKIN_SEEDED", seed.getEmployeeUserId());
        return toResponse(seed);
    }

    // TEMPORARY — delete with FR-004 (see PlaceholderCheckinSeed entity Javadoc).
    @Transactional(readOnly = true)
    public List<PlaceholderCheckinResponse> listPlaceholderCheckins(LocalDate from, LocalDate to) {
        return placeholderCheckinSeedRepository.findByWorkDateBetween(from, to).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
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

    private PlaceholderCheckinResponse toResponse(PlaceholderCheckinSeed seed) {
        String fullName = employeeRepository.findById(seed.getEmployeeUserId())
                .map(Employee::getFullName).orElse(null);
        return PlaceholderCheckinResponse.builder()
                .id(seed.getId())
                .employeeUserId(seed.getEmployeeUserId())
                .employeeFullName(fullName)
                .workDate(seed.getWorkDate())
                .shiftStartTime(seed.getShiftStartTime())
                .checkinTime(seed.getCheckinTime())
                .lateThresholdMinutes(seed.getLateThresholdMinutes())
                .createdAt(seed.getCreatedAt())
                .build();
    }
}

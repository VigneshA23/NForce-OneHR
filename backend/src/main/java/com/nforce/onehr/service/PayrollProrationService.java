package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayrollProrationResponse;
import com.nforce.onehr.dto.payroll.RecomputeProrationRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * US-C07 (ONEHR-613), Phase 2 — see {@link PayrollProration}'s own doc comment for the formula
 * and its source/limits. Reuses {@link PayGroup#resolveCycle} exactly like
 * {@link PayrollLopCalculationService}, and {@link LeaveBalanceAvailabilityService} for the exit
 * leave-encashment figure, so "available paid leave" can't drift between the two stories.
 */
@Service
@RequiredArgsConstructor
public class PayrollProrationService {

    private final PayrollProrationRepository payrollProrationRepository;
    private final EmployeeRepository employeeRepository;
    private final PayGroupRepository payGroupRepository;
    private final LeaveBalanceAvailabilityService leaveBalanceAvailabilityService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public PayrollProrationResponse recompute(RecomputeProrationRequest req, String actorEmail) {
        Employee employee = employeeRepository.findById(req.getEmployeeId())
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + req.getEmployeeId()));
        PayCycleWindow window = resolveCycle(req.getPayGroupId(), req.getReferenceDate());

        LocalDate joiningDate = employee.getJoiningDate();
        LocalDate exitDate = employee.getLastWorkingDay();

        if (joiningDate.isAfter(window.end()) || (exitDate != null && exitDate.isBefore(window.start()))) {
            throw new IllegalArgumentException(
                    "Employee " + employee.getUserId() + " was not employed during cycle " + window.start() + "-" + window.end());
        }

        LocalDate effectiveStart = joiningDate.isAfter(window.start()) ? joiningDate : window.start();
        LocalDate effectiveEnd = (exitDate != null && exitDate.isBefore(window.end())) ? exitDate : window.end();

        int cycleDays = window.dayCount();
        int employedDays = (int) ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1;
        BigDecimal prorationFactor = BigDecimal.valueOf(employedDays)
                .divide(BigDecimal.valueOf(cycleDays), 4, RoundingMode.HALF_UP);

        boolean isJoinerCycle = joiningDate.isAfter(window.start());
        boolean isExitCycle = exitDate != null && !exitDate.isBefore(window.start()) && !exitDate.isAfter(window.end());
        String prorationType = isJoinerCycle && isExitCycle ? ProrationType.JOINER_AND_EXIT
                : isJoinerCycle ? ProrationType.JOINER
                : isExitCycle ? ProrationType.EXIT
                : ProrationType.FULL_CYCLE;

        BigDecimal leaveEncashmentDays = isExitCycle
                ? leaveBalanceAvailabilityService.availablePaidDays(employee.getUserId(), window.start().getYear())
                : null;

        Optional<PayrollProration> existing = payrollProrationRepository
                .findByEmployeeIdAndCycleStartAndCycleEnd(employee.getUserId(), window.start(), window.end());
        String before = existing.map(p -> auditSnapshot.toJson(snapshot(p))).orElse(null);

        PayrollProration proration = existing.orElseGet(() -> PayrollProration.builder()
                .employeeId(employee.getUserId())
                .cycleStart(window.start())
                .cycleEnd(window.end())
                .build());
        proration.setCycleDays(cycleDays);
        proration.setEmployedDays(employedDays);
        proration.setProrationFactor(prorationFactor);
        proration.setProrationType(prorationType);
        proration.setLeaveEncashmentDays(leaveEncashmentDays);
        proration.setComputedAt(LocalDateTime.now());
        proration = payrollProrationRepository.save(proration);

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(snapshot(proration));
        auditService.log(actor.getId(), "PAYROLL_PRORATION_COMPUTED", proration.getId(), before, after);

        return toResponse(proration);
    }

    @Transactional(readOnly = true)
    public PayrollProrationResponse get(UUID employeeId, UUID payGroupId, LocalDate referenceDate) {
        PayCycleWindow window = resolveCycle(payGroupId, referenceDate);
        PayrollProration proration = payrollProrationRepository
                .findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, window.start(), window.end())
                .orElseThrow(() -> new NoSuchElementException(
                        "No proration calculation for employee " + employeeId + " in cycle " + window.start() + "-" + window.end()));
        return toResponse(proration);
    }

    private PayCycleWindow resolveCycle(UUID payGroupId, LocalDate referenceDate) {
        PayGroup payGroup = payGroupRepository.findById(payGroupId)
                .orElseThrow(() -> new NoSuchElementException("Pay group not found: " + payGroupId));
        return payGroup.resolveCycle(referenceDate != null ? referenceDate : LocalDate.now());
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> snapshot(PayrollProration p) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("employedDays", p.getEmployedDays());
        snapshot.put("cycleDays", p.getCycleDays());
        snapshot.put("prorationFactor", p.getProrationFactor());
        snapshot.put("prorationType", p.getProrationType());
        snapshot.put("leaveEncashmentDays", p.getLeaveEncashmentDays());
        return snapshot;
    }

    private PayrollProrationResponse toResponse(PayrollProration p) {
        return PayrollProrationResponse.builder()
                .id(p.getId())
                .employeeId(p.getEmployeeId())
                .cycleStart(p.getCycleStart())
                .cycleEnd(p.getCycleEnd())
                .cycleDays(p.getCycleDays())
                .employedDays(p.getEmployedDays())
                .prorationFactor(p.getProrationFactor())
                .prorationType(p.getProrationType())
                .leaveEncashmentDays(p.getLeaveEncashmentDays())
                .computedAt(p.getComputedAt())
                .build();
    }
}

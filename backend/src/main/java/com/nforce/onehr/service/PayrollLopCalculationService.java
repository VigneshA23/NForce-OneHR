package com.nforce.onehr.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.payroll.OverrideLopRequest;
import com.nforce.onehr.dto.payroll.PayrollLopCalculationResponse;
import com.nforce.onehr.dto.payroll.RecomputeLopRequest;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * US-C04 (ONEHR-610) / US-C05 (ONEHR-611), Phase 2 — computes Loss-of-Pay days for one employee
 * over one {@link PayGroup#resolveCycle} window, and lets an HR Admin override the result.
 *
 * <p><b>Formula source and its limits:</b> the divisor-is-the-pay-cycle's-own-day-count rule and
 * the per-component proration shape come from a Keka product walkthrough (2026-11-03/04), not a
 * confirmed BA spec — its own notes flag the exact daily-rate-vs-proportional-gross formula,
 * half-day "no attendance" support, and per-pay-group divisor variation as still open. This
 * service only computes and stores the LOP day count (Phase 2's own scope); nothing here applies
 * it to a payslip yet — that's Phase 3, once {@code PayrollRun} exists to consume it.
 *
 * <p><b>Three components, summed into {@code computedLopDays}:</b>
 * <ol>
 *   <li><b>Unpaid leave</b> — approved {@link LeaveRequest}s whose {@link LeaveType} is
 *       {@link LeaveTypeClassification#UNPAID}, attributed to whichever cycle contains the
 *       request's startDate. (Simplification: a request spanning a cycle boundary is NOT split —
 *       its full totalDays goes to the start cycle. Revisit if that matters in practice.)</li>
 *   <li><b>Penalty-driven LOP</b> — {@link AttendancePenalty#getLopDays()} for still-active
 *       ({@code PENDING_REVIEW}) penalties with {@code incidentDate} in the cycle.</li>
 *   <li><b>No-attendance days</b> — {@link WorkingDayService} expected-working dates with no
 *       {@link Attendance} row, no penalty, no leave. Per the Keka walkthrough ("if an employee
 *       has no paid leave balance, the system automatically treats the absence as unpaid"): a date
 *       is auto-counted as LOP only when the employee's total available PAID leave balance (for
 *       the cycle start's year) is zero or less; otherwise it's left for a human to resolve
 *       (US-C23's "No Attendance" review step) and listed in {@code needsManualReviewDates}
 *       instead of being silently counted.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class PayrollLopCalculationService {

    private static final String LEAVE_STATUS_APPROVED = "APPROVED";

    private final PayrollLopCalculationRepository payrollLopCalculationRepository;
    private final EmployeeRepository employeeRepository;
    private final PayGroupRepository payGroupRepository;
    private final AttendanceRepository attendanceRepository;
    private final AttendancePenaltyRepository attendancePenaltyRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final WorkingDayService workingDayService;
    private final LeaveBalanceAvailabilityService leaveBalanceAvailabilityService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public PayrollLopCalculationResponse recompute(RecomputeLopRequest req, String actorEmail) {
        Employee employee = employeeRepository.findById(req.getEmployeeId())
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + req.getEmployeeId()));
        PayCycleWindow window = resolveCycle(req.getPayGroupId(), req.getReferenceDate());

        BigDecimal unpaidLeaveDays = computeUnpaidLeaveDays(employee.getUserId(), window);
        List<AttendancePenalty> penalties = attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                employee.getUserId(), window.start(), window.end(), AttendancePenaltyStatus.PENDING_REVIEW);
        BigDecimal penaltyLopDays = penalties.stream()
                .map(AttendancePenalty::getLopDays)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<LocalDate> penalizedDates = penalties.stream().map(AttendancePenalty::getIncidentDate).collect(Collectors.toSet());

        List<LocalDate> noAttendanceDates = computeNoAttendanceDates(employee, window, penalizedDates);
        boolean hasAvailablePaidBalance = hasAvailablePaidLeaveBalance(employee.getUserId(), window.start().getYear());
        BigDecimal autoLopNoAttendanceDays = hasAvailablePaidBalance
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(noAttendanceDates.size());
        List<LocalDate> needsManualReviewDates = hasAvailablePaidBalance ? noAttendanceDates : List.of();

        BigDecimal computedLopDays = unpaidLeaveDays.add(penaltyLopDays).add(autoLopNoAttendanceDays);

        Optional<PayrollLopCalculation> existing = payrollLopCalculationRepository
                .findByEmployeeIdAndCycleStartAndCycleEnd(employee.getUserId(), window.start(), window.end());
        String before = existing.map(c -> auditSnapshot.toJson(computedSnapshot(c))).orElse(null);

        PayrollLopCalculation calc = existing.orElseGet(() -> PayrollLopCalculation.builder()
                .employeeId(employee.getUserId())
                .cycleStart(window.start())
                .cycleEnd(window.end())
                .build());
        calc.setUnpaidLeaveDays(unpaidLeaveDays);
        calc.setPenaltyLopDays(penaltyLopDays);
        calc.setAutoLopNoAttendanceDays(autoLopNoAttendanceDays);
        calc.setComputedLopDays(computedLopDays);
        calc.setNeedsManualReviewDates(writeDates(needsManualReviewDates));
        calc.setComputedAt(LocalDateTime.now());
        calc = payrollLopCalculationRepository.save(calc);

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(computedSnapshot(calc));
        auditService.log(actor.getId(), "PAYROLL_LOP_COMPUTED", calc.getId(), before, after);

        return toResponse(calc);
    }

    @Transactional
    public PayrollLopCalculationResponse override(OverrideLopRequest req, String actorEmail) {
        PayCycleWindow window = resolveCycle(req.getPayGroupId(), req.getReferenceDate());
        PayrollLopCalculation calc = payrollLopCalculationRepository
                .findByEmployeeIdAndCycleStartAndCycleEnd(req.getEmployeeId(), window.start(), window.end())
                .orElseThrow(() -> new IllegalStateException(
                        "No LOP calculation for this employee/cycle yet — recompute before overriding"));

        String before = auditSnapshot.toJson(overrideSnapshot(calc));

        User actor = requireActor(actorEmail);
        calc.setOverrideLopDays(req.getOverrideLopDays());
        calc.setOverrideReason(req.getOverrideReason());
        calc.setOverriddenBy(actor.getId());
        calc.setOverriddenAt(LocalDateTime.now());
        calc = payrollLopCalculationRepository.save(calc);

        String after = auditSnapshot.toJson(overrideSnapshot(calc));
        auditService.log(actor.getId(), "PAYROLL_LOP_OVERRIDDEN", calc.getId(), before, after);

        return toResponse(calc);
    }

    @Transactional(readOnly = true)
    public PayrollLopCalculationResponse get(UUID employeeId, UUID payGroupId, LocalDate referenceDate) {
        PayCycleWindow window = resolveCycle(payGroupId, referenceDate);
        PayrollLopCalculation calc = payrollLopCalculationRepository
                .findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, window.start(), window.end())
                .orElseThrow(() -> new NoSuchElementException(
                        "No LOP calculation for employee " + employeeId + " in cycle " + window.start() + "-" + window.end()));
        return toResponse(calc);
    }

    private PayCycleWindow resolveCycle(UUID payGroupId, LocalDate referenceDate) {
        PayGroup payGroup = payGroupRepository.findById(payGroupId)
                .orElseThrow(() -> new NoSuchElementException("Pay group not found: " + payGroupId));
        return payGroup.resolveCycle(referenceDate != null ? referenceDate : LocalDate.now());
    }

    private BigDecimal computeUnpaidLeaveDays(UUID employeeId, PayCycleWindow window) {
        List<UUID> unpaidLeaveTypeIds = leaveTypeRepository.findAll().stream()
                .filter(lt -> !lt.isPaid())
                .map(LeaveType::getId)
                .collect(Collectors.toList());
        if (unpaidLeaveTypeIds.isEmpty()) return BigDecimal.ZERO;

        BigDecimal sum = leaveRequestRepository.sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                employeeId, unpaidLeaveTypeIds, LEAVE_STATUS_APPROVED, window.start(), window.end());
        return sum != null ? sum : BigDecimal.ZERO;
    }

    private List<LocalDate> computeNoAttendanceDates(Employee employee, PayCycleWindow window, Set<LocalDate> penalizedDates) {
        WorkingDaySchedule schedule = workingDayService.computeExpectedWorkingDays(employee, window.start(), window.end());
        return schedule.getWorkingDates().stream()
                .filter(date -> !penalizedDates.contains(date))
                .filter(date -> attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), date).isEmpty())
                .sorted()
                .collect(Collectors.toList());
    }

    private boolean hasAvailablePaidLeaveBalance(UUID employeeId, int year) {
        return leaveBalanceAvailabilityService.availablePaidDays(employeeId, year).compareTo(BigDecimal.ZERO) > 0;
    }

    private String writeDates(List<LocalDate> dates) {
        try {
            return objectMapper.writeValueAsString(dates);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize needsManualReviewDates", e);
        }
    }

    private List<LocalDate> readDates(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<LocalDate>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize needsManualReviewDates", e);
        }
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> computedSnapshot(PayrollLopCalculation c) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("unpaidLeaveDays", c.getUnpaidLeaveDays());
        snapshot.put("penaltyLopDays", c.getPenaltyLopDays());
        snapshot.put("autoLopNoAttendanceDays", c.getAutoLopNoAttendanceDays());
        snapshot.put("computedLopDays", c.getComputedLopDays());
        return snapshot;
    }

    private Map<String, Object> overrideSnapshot(PayrollLopCalculation c) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("overrideLopDays", c.getOverrideLopDays());
        snapshot.put("overrideReason", c.getOverrideReason());
        return snapshot;
    }

    private PayrollLopCalculationResponse toResponse(PayrollLopCalculation c) {
        BigDecimal effective = c.getOverrideLopDays() != null ? c.getOverrideLopDays() : c.getComputedLopDays();
        return PayrollLopCalculationResponse.builder()
                .id(c.getId())
                .employeeId(c.getEmployeeId())
                .cycleStart(c.getCycleStart())
                .cycleEnd(c.getCycleEnd())
                .unpaidLeaveDays(c.getUnpaidLeaveDays())
                .penaltyLopDays(c.getPenaltyLopDays())
                .autoLopNoAttendanceDays(c.getAutoLopNoAttendanceDays())
                .computedLopDays(c.getComputedLopDays())
                .needsManualReviewDates(readDates(c.getNeedsManualReviewDates()))
                .computedAt(c.getComputedAt())
                .overrideLopDays(c.getOverrideLopDays())
                .overrideReason(c.getOverrideReason())
                .overriddenBy(c.getOverriddenBy())
                .overriddenAt(c.getOverriddenAt())
                .effectiveLopDays(effective)
                .build();
    }
}

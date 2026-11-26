package com.nforce.onehr.service;

import com.nforce.onehr.dto.overtime.OtDtos.CalculationDto;
import com.nforce.onehr.dto.overtime.OtDtos.DecisionRequest;
import com.nforce.onehr.dto.overtime.OtDtos.HistoryDto;
import com.nforce.onehr.dto.overtime.OtDtos.RunRequest;
import com.nforce.onehr.dto.overtime.OtDtos.RunResult;
import com.nforce.onehr.dto.overtime.OtDtos.SkippedRecord;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeCompensation;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.Holiday;
import com.nforce.onehr.entity.OtCalculation;
import com.nforce.onehr.entity.OtCalculationHistory;
import com.nforce.onehr.entity.OtRateRule;
import com.nforce.onehr.entity.OtSettings;
import com.nforce.onehr.entity.OvertimeRequest;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.OtAdjustmentRepository;
import com.nforce.onehr.repository.OtCalculationHistoryRepository;
import com.nforce.onehr.repository.OtCalculationRepository;
import com.nforce.onehr.repository.OvertimeRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Attendance-driven overtime calculation.
 *
 * <p>Formula (configurable through {@link OtSettings}):
 * <pre>
 *   scheduledMinutes = shift (end - start) - break          (0-OT baseline on a working day)
 *   otMinutes        = worked - scheduled                     (working day)
 *                    = worked                                 (weekly off / holiday: all of it is OT)
 *   otMinutes        = floor(otMinutes to rounding step), dropped when below the minimum threshold
 *   monthlyPay       = CTC / 12 effective on the work date
 *   hourlyRate       = monthlyPay / (workingDaysPerMonth * scheduledHours)
 *   otAmount         = otHours * hourlyRate * multiplier (from the effective-dated rate rule)
 * </pre>
 * Incomplete or invalid attendance rows are skipped (and reported) until corrected. One row per
 * employee and date is enforced by a unique constraint; rows that are no longer CALCULATED or were
 * adjusted are never overwritten by a re-run.
 */
@Service
@RequiredArgsConstructor
public class OtCalculationService {

    private static final int MAX_RANGE_DAYS = 62;
    private static final int MAX_DAY_MINUTES = 24 * 60;

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeShiftAssignmentResolver shiftAssignmentResolver;
    private final ShiftVersionResolver shiftVersionResolver;
    private final WorkingDayService workingDayService;
    private final HolidayRepository holidayRepository;
    private final EmployeeCompensationRepository compensationRepository;
    private final OvertimeRequestRepository requestRepository;
    private final OtCalculationRepository calculationRepository;
    private final OtCalculationHistoryRepository historyRepository;
    private final OtAdjustmentRepository adjustmentRepository;
    private final OtConfigService configService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public RunResult run(RunRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        LocalDate from = req.from();
        LocalDate to = req.to();
        if (to.isBefore(from)) throw new IllegalArgumentException("'To' date cannot be before 'from' date");
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new IllegalArgumentException("Calculation range cannot exceed " + MAX_RANGE_DAYS + " days");
        }
        if (to.isAfter(LocalDate.now())) throw new IllegalArgumentException("Cannot calculate overtime for future dates");

        OtSettings settings = configService.settings();
        List<OtRateRule> rules = configService.activeRules();

        List<Attendance> records = new ArrayList<>();
        if (req.employeeIds() != null && !req.employeeIds().isEmpty()) {
            records.addAll(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(req.employeeIds(), from, to));
        } else {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                records.addAll(attendanceRepository.findByWorkDate(d));
            }
        }
        Set<UUID> employeeIds = records.stream().map(Attendance::getEmployeeUserId).collect(Collectors.toSet());
        if (employeeIds.isEmpty()) return new RunResult(0, 0, 0, List.of());

        Map<UUID, Employee> employees = employeeRepository.findAllByIdWithDepartment(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));
        WorkingDayService.WeeklyOffBatchResolver weeklyOff = workingDayService.weeklyOffDaysResolver(employeeIds);
        Set<UUID> locationIds = employees.values().stream()
                .filter(e -> e.getLocation() != null).map(e -> e.getLocation().getId()).collect(Collectors.toSet());
        Map<UUID, Set<LocalDate>> holidaysByLocation = new HashMap<>();
        if (!locationIds.isEmpty()) {
            for (Holiday h : holidayRepository.findByLocation_IdInAndActiveTrue(locationIds)) {
                holidaysByLocation.computeIfAbsent(h.getLocation().getId(), k -> new HashSet<>()).add(h.getHolidayDate());
            }
        }
        Map<String, OtCalculation> existing = calculationRepository
                .findByEmployeeUserIdInAndWorkDateBetween(employeeIds, from, to).stream()
                .collect(Collectors.toMap(c -> key(c.getEmployeeUserId(), c.getWorkDate()), c -> c));
        Map<String, OvertimeRequest> approvedRequests = requestRepository
                .findByEmployeeUserIdInAndWorkDateBetween(employeeIds, from, to).stream()
                .filter(r -> "APPROVED".equals(r.getStatus()))
                .collect(Collectors.toMap(r -> key(r.getEmployeeUserId(), r.getWorkDate()), r -> r, (a, b) -> a));

        int created = 0, updated = 0, unchanged = 0;
        List<SkippedRecord> skipped = new ArrayList<>();

        for (Attendance att : records) {
            Employee emp = employees.get(att.getEmployeeUserId());
            if (emp == null) continue;
            LocalDate date = att.getWorkDate();
            if (date.isBefore(from) || date.isAfter(to)) continue;
            String k = key(emp.getUserId(), date);
            OtCalculation prior = existing.get(k);

            // Never touch a row that has moved on from CALCULATED or was manually adjusted.
            if (prior != null && (!OtCalculation.CALCULATED.equals(prior.getStatus()) || prior.isAdjusted())) {
                unchanged++;
                continue;
            }

            String invalid = validateAttendance(att);
            if (invalid != null) {
                skipped.add(new SkippedRecord(emp.getUserId(), emp.getFullName(), date, invalid));
                continue;
            }
            int worked = att.getWorkedMinutes() != null && att.getWorkedMinutes() > 0 ? att.getWorkedMinutes()
                    : (int) Duration.between(att.getCheckInAt(), att.getCheckOutAt()).toMinutes();

            Optional<EmployeeShiftAssignment> assignment = shiftAssignmentResolver.resolveIfPresent(emp.getUserId(), date);
            if (assignment.isEmpty()) {
                skipped.add(new SkippedRecord(emp.getUserId(), emp.getFullName(), date, "No shift assigned"));
                continue;
            }
            var shift = assignment.get().getShift();
            Optional<ShiftVersion> version = shiftVersionResolver.resolveIfPresent(shift, date);
            if (version.isEmpty()) {
                skipped.add(new SkippedRecord(emp.getUserId(), emp.getFullName(), date, "Shift has no timings for this date"));
                continue;
            }
            int scheduled = scheduledMinutes(version.get());

            String dayType = dayType(emp, date, weeklyOff, holidaysByLocation);
            int baseline = "WEEKDAY".equals(dayType) ? scheduled : 0;
            int rawOt = worked - baseline;
            if (rawOt < settings.getMinOtMinutes() || rawOt <= 0) continue; // no eligible OT

            int otMinutes = rawOt - (rawOt % Math.max(1, settings.getRoundingMinutes()));

            OvertimeRequest request = approvedRequests.get(k);
            if (settings.isRequireApprovedRequest()) {
                if (request == null) {
                    skipped.add(new SkippedRecord(emp.getUserId(), emp.getFullName(), date, "No approved OT request"));
                    continue;
                }
                long requested = Duration.between(request.getRequestedStart(), request.getRequestedEnd()).toMinutes();
                otMinutes = (int) Math.min(otMinutes, requested);
            }
            if (otMinutes <= 0) continue;

            UUID deptId = emp.getDepartment() != null ? emp.getDepartment().getId() : null;
            Optional<OtRateRule> rule = configService.resolveRule(rules, dayType, deptId, shift.getId(), date);
            if (rule.isEmpty()) {
                skipped.add(new SkippedRecord(emp.getUserId(), emp.getFullName(), date, "No OT rate rule applies"));
                continue;
            }
            Optional<EmployeeCompensation> comp = compensationRepository.findByEmployeeIdAndEffectiveAt(emp.getUserId(), date);
            if (comp.isEmpty()) {
                skipped.add(new SkippedRecord(emp.getUserId(), emp.getFullName(), date, "No compensation on this date"));
                continue;
            }
            BigDecimal scheduledHours = BigDecimal.valueOf(scheduled > 0 ? scheduled : 480)
                    .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
            BigDecimal hourly = comp.get().getCtc().divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP)
                    .divide(settings.getWorkingDaysPerMonth().multiply(scheduledHours), 4, RoundingMode.HALF_UP);
            BigDecimal multiplier = rule.get().getMultiplier();
            BigDecimal amount = amountFor(otMinutes, hourly, multiplier);

            if (prior == null) {
                OtCalculation calc = OtCalculation.builder()
                        .employeeUserId(emp.getUserId()).workDate(date)
                        .shiftId(shift.getId()).shiftName(shift.getName())
                        .scheduledMinutes(scheduled).workedMinutes(worked).otMinutes(otMinutes)
                        .dayType(dayType).rateRuleId(rule.get().getId()).multiplier(multiplier)
                        .hourlyRate(hourly).otAmount(amount)
                        .otRequestId(request != null ? request.getId() : null)
                        .status(OtCalculation.CALCULATED).build();
                calc = calculationRepository.save(calc);
                record(calc, "CALCULATED", "Calculated from attendance", actor.getId());
                created++;
            } else if (prior.getOtMinutes() == otMinutes && prior.getOtAmount().compareTo(amount) == 0
                    && prior.getWorkedMinutes() == worked) {
                unchanged++;
            } else {
                prior.setShiftId(shift.getId());
                prior.setShiftName(shift.getName());
                prior.setScheduledMinutes(scheduled);
                prior.setWorkedMinutes(worked);
                prior.setOtMinutes(otMinutes);
                prior.setDayType(dayType);
                prior.setRateRuleId(rule.get().getId());
                prior.setMultiplier(multiplier);
                prior.setHourlyRate(hourly);
                prior.setOtAmount(amount);
                prior.setOtRequestId(request != null ? request.getId() : null);
                calculationRepository.save(prior);
                record(prior, "RECALCULATED", "Recalculated after attendance or policy change", actor.getId());
                updated++;
            }
        }

        auditService.log(actor.getId(), "OT_CALCULATION_RUN", actor.getId());
        if (!skipped.isEmpty()) notifyHrOfDiscrepancies(actor, skipped.size(), from, to);
        return new RunResult(created, updated, unchanged, skipped);
    }

    @Transactional(readOnly = true)
    public List<CalculationDto> list(LocalDate from, LocalDate to, String status, UUID departmentId, UUID employeeId) {
        List<OtCalculation> rows = calculationRepository.findByWorkDateBetweenOrderByWorkDateDescEmployeeUserIdAsc(from, to);
        rows = rows.stream()
                .filter(c -> status == null || status.isBlank() || status.equals(c.getStatus()))
                .filter(c -> employeeId == null || employeeId.equals(c.getEmployeeUserId()))
                .toList();
        if (rows.isEmpty()) return List.of();
        Map<UUID, Employee> employees = employeeRepository
                .findAllByIdWithDepartment(rows.stream().map(OtCalculation::getEmployeeUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Employee::getUserId, e -> e));
        return rows.stream()
                .filter(c -> departmentId == null || (employees.get(c.getEmployeeUserId()) != null
                        && employees.get(c.getEmployeeUserId()).getDepartment() != null
                        && departmentId.equals(employees.get(c.getEmployeeUserId()).getDepartment().getId())))
                .map(c -> toDto(c, employees.get(c.getEmployeeUserId())))
                .toList();
    }

    /** HR review: approve (eligible for payroll) or reject a batch of calculated rows. */
    @Transactional
    public int decide(DecisionRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        boolean approve = "APPROVE".equals(req.decision());
        if (!approve && (req.comment() == null || req.comment().isBlank())) {
            throw new IllegalArgumentException("A comment is required when rejecting overtime");
        }
        int count = 0;
        for (OtCalculation c : calculationRepository.findAllById(new LinkedHashSet<>(req.ids()))) {
            if (c.getPayrollRunId() != null) {
                throw new IllegalStateException("OT for " + c.getWorkDate() + " is already part of a payroll run");
            }
            if (adjustmentRepository.existsByCalculationIdAndStatus(c.getId(), "PENDING")) {
                throw new IllegalStateException("OT for " + c.getWorkDate() + " has a pending adjustment");
            }
            c.setStatus(approve ? OtCalculation.APPROVED : OtCalculation.REJECTED);
            c.setReviewedBy(actor.getId());
            c.setReviewedAt(LocalDateTime.now());
            c.setReviewComment(req.comment());
            calculationRepository.save(c);
            record(c, approve ? "APPROVED" : "REJECTED", req.comment(), actor.getId());
            auditService.log(actor.getId(), approve ? "OT_CALCULATION_APPROVED" : "OT_CALCULATION_REJECTED", c.getId());
            notificationService.send(c.getEmployeeUserId(), approve ? "OVERTIME_APPROVED" : "OVERTIME_REJECTED",
                    approve ? "Overtime Approved" : "Overtime Rejected",
                    "Your overtime for " + c.getWorkDate() + (approve ? " has been approved for payroll."
                            : " was not approved" + (req.comment() != null ? ". Reason: " + req.comment().trim() : ".")),
                    "/my-requests?type=OVERTIME");
            count++;
        }
        return count;
    }

    @Transactional(readOnly = true)
    public List<HistoryDto> history(UUID calculationId) {
        List<OtCalculationHistory> rows = historyRepository.findByCalculationIdOrderByCreatedAtDesc(calculationId);
        Set<UUID> actorIds = rows.stream().map(OtCalculationHistory::getActorId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = actorIds.isEmpty() ? Map.of()
                : employeeRepository.findNamesByUserIds(actorIds).stream()
                        .collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1]));
        return rows.stream().map(h -> new HistoryDto(h.getId(), h.getEvent(), h.getWorkedMinutes(), h.getOtMinutes(),
                h.getMultiplier(), h.getHourlyRate(), h.getOtAmount(), h.getStatus(), h.getNote(),
                h.getActorId() == null ? null : names.get(h.getActorId()), h.getCreatedAt())).toList();
    }

    // ------------------------------------------------------------------ shared helpers

    public static BigDecimal amountFor(int otMinutes, BigDecimal hourlyRate, BigDecimal multiplier) {
        return BigDecimal.valueOf(otMinutes).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP)
                .multiply(hourlyRate).multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    void record(OtCalculation c, String event, String note, UUID actorId) {
        historyRepository.save(OtCalculationHistory.builder()
                .calculationId(c.getId()).event(event).workedMinutes(c.getWorkedMinutes())
                .otMinutes(c.getOtMinutes()).multiplier(c.getMultiplier()).hourlyRate(c.getHourlyRate())
                .otAmount(c.getOtAmount()).status(c.getStatus()).note(note).actorId(actorId).build());
    }

    private String validateAttendance(Attendance a) {
        if (a.getCheckInAt() == null) return "Missing check-in";
        if (a.getCheckOutAt() == null) return "Missing check-out (incomplete attendance)";
        if (!a.getCheckOutAt().isAfter(a.getCheckInAt())) return "Check-out is not after check-in";
        long minutes = Duration.between(a.getCheckInAt(), a.getCheckOutAt()).toMinutes();
        if (minutes > MAX_DAY_MINUTES) return "Attendance span exceeds 24 hours";
        if (a.getWorkedMinutes() != null && (a.getWorkedMinutes() < 0 || a.getWorkedMinutes() > MAX_DAY_MINUTES)) {
            return "Invalid worked duration";
        }
        return null;
    }

    private int scheduledMinutes(ShiftVersion v) {
        LocalTime start = v.getStartTime();
        LocalTime end = v.getEndTime();
        long span = Duration.between(start, end).toMinutes();
        if (span <= 0) span += MAX_DAY_MINUTES; // overnight shift
        int brk = v.getBreakMinutes() != null ? v.getBreakMinutes() : 0;
        return (int) Math.max(0, span - brk);
    }

    private String dayType(Employee emp, LocalDate date, WorkingDayService.WeeklyOffBatchResolver weeklyOff,
                           Map<UUID, Set<LocalDate>> holidaysByLocation) {
        if (emp.getLocation() != null
                && holidaysByLocation.getOrDefault(emp.getLocation().getId(), Set.of()).contains(date)) {
            return "HOLIDAY";
        }
        Set<DayOfWeek> off = weeklyOff.offDaysOn(emp.getUserId(), date);
        return off != null && off.contains(date.getDayOfWeek()) ? "WEEKLY_OFF" : "WEEKDAY";
    }

    CalculationDto toDto(OtCalculation c, Employee e) {
        return new CalculationDto(c.getId(), c.getEmployeeUserId(), e != null ? e.getEmployeeCode() : null,
                e != null ? e.getFullName() : "Unknown",
                e != null && e.getDepartment() != null ? e.getDepartment().getName() : null, c.getShiftName(),
                c.getWorkDate(), c.getScheduledMinutes(), c.getWorkedMinutes(), c.getOtMinutes(), c.getDayType(),
                c.getMultiplier(), c.getHourlyRate(), c.getOtAmount(), c.getStatus(), c.isAdjusted(),
                adjustmentRepository.existsByCalculationIdAndStatus(c.getId(), "PENDING"),
                c.getReviewComment(), c.getPayrollRunId(), c.getCalculatedAt());
    }

    private void notifyHrOfDiscrepancies(User actor, int count, LocalDate from, LocalDate to) {
        notificationService.send(actor.getId(), "ATTENDANCE", "OT calculation needs attention",
                count + " attendance record(s) between " + from + " and " + to
                        + " were excluded from overtime calculation until corrected.", "/overtime-management");
    }

    private static String key(UUID employeeId, LocalDate date) {
        return employeeId + "|" + date;
    }

    private User actor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}

package com.nforce.onehr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.MyPenaltiesResponse;
import com.nforce.onehr.dto.attendance.MyPenaltyResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.dto.attendance.PenaltyDisputeResponse;
import com.nforce.onehr.entity.AttendanceException;
import com.nforce.onehr.entity.AttendancePenaltyDispute;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PN-01 — the employee's own "My Penalties" view. Read-only: every figure is read back from the
 * persisted {@link AttendancePenalty} snapshot (deduction / leave split / LOP) and the detected
 * {@link AttendanceException} / {@link Attendance} facts, never recomputed — so what the employee
 * sees is exactly what was applied to the payslip.
 *
 * <p>Two kinds of row: persisted penalties of any status, and detected incidents that are still
 * inside the policy's buffer period and could yet become one ({@code IN_BUFFER}). The latter is
 * where the corrective actions (Regularize / Apply Leave) are offered — once the buffer has
 * elapsed the engine has already decided (see ConfiguredAttendancePolicyEngine#decideApplyPenalty).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MyPenaltyService {

    public static final String IN_BUFFER = "IN_BUFFER";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> ACTIVE_REGULARIZATION_STATUSES = Set.of("PENDING", "PARTIALLY_APPROVED", "APPROVED");
    private static final Set<String> PENALIZABLE_TYPES = Set.of(
            ExceptionType.LATE_ARRIVAL, ExceptionType.MISSING_PUNCH, ExceptionType.WORK_HOURS_SHORTAGE,
            ExceptionType.EARLY_DEPARTURE, ExceptionType.EXCESS_BREAK);

    private final AttendancePenaltyRepository attendancePenaltyRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftVersionResolver shiftVersionResolver;
    private final PenalizationPolicyResolutionService policyResolutionService;
    private final AttendanceProperties attendanceProperties;
    private final ObjectMapper objectMapper;
    private final PenaltyDisputeService penaltyDisputeService;

    @Transactional(readOnly = true)
    public MyPenaltiesResponse getMine(String actorEmail, LocalDate from, LocalDate to) {
        return getMine(actorEmail, from, to, LocalDate.now(ZoneId.of(attendanceProperties.getZone())));
    }

    MyPenaltiesResponse getMine(String actorEmail, LocalDate from, LocalDate to, LocalDate today) {
        Employee employee = employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("A valid date range is required.");
        }
        UUID userId = employee.getUserId();
        PenalizationPolicyVersion policy = policyResolutionService.resolveEffectiveVersionForEmployee(employee, today);
        int buffer = policy != null && policy.getBufferPeriodDays() != null ? Math.max(0, policy.getBufferPeriodDays()) : 0;

        List<AttendancePenalty> penalties = attendancePenaltyRepository
                .findByEmployeeUserIdAndIncidentDateBetween(userId, from, to);
        List<AttendanceException> incidents = attendanceExceptionRepository
                .findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(List.of(userId), from, to);
        Map<LocalDate, Attendance> attendanceByDate = attendanceRepository
                .findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(userId, from, to).stream()
                .collect(Collectors.toMap(Attendance::getWorkDate, Function.identity(), (a, b) -> a));
        Set<LocalDate> openRegularizationDates = regularizationRequestRepository
                .findByEmployeeUserIdInAndAttendanceDateBetween(List.of(userId), from, to).stream()
                .filter(r -> ACTIVE_REGULARIZATION_STATUSES.contains(r.getStatus()))
                .map(RegularizationRequest::getAttendanceDate)
                .collect(Collectors.toSet());
        Map<String, AttendanceException> incidentByKey = new HashMap<>();
        incidents.forEach(e -> incidentByKey.putIfAbsent(e.getExceptionDate() + "|" + e.getExceptionType(), e));

        List<MyPenaltyResponse> rows = new ArrayList<>();
        Set<String> penalizedKeys = new HashSet<>();
        Map<UUID, AttendancePenaltyDispute> disputes = penaltyDisputeService
                .byPenaltyIds(penalties.stream().map(AttendancePenalty::getId).toList());
        for (AttendancePenalty p : penalties) {
            penalizedKeys.add(p.getIncidentDate() + "|" + p.getDiscrepancyType());
            MyPenaltyResponse row = toRow(p, incidentByKey.get(p.getIncidentDate() + "|" + p.getDiscrepancyType()),
                    attendanceByDate.get(p.getIncidentDate()), openRegularizationDates.contains(p.getIncidentDate()));
            AttendancePenaltyDispute dispute = disputes.get(p.getId());
            if (dispute != null) {
                row.setDispute(PenaltyDisputeResponse.builder()
                        .id(dispute.getId()).penaltyId(p.getId()).status(dispute.getStatus())
                        .reason(dispute.getReason()).evidence(dispute.getEvidence())
                        .managerRecommendation(dispute.getManagerRecommendation()).managerComment(dispute.getManagerComment())
                        .managerActedAt(dispute.getManagerActedAt())
                        .resolutionComment(dispute.getResolutionComment()).resolvedAt(dispute.getResolvedAt())
                        .createdAt(dispute.getCreatedAt()).build());
            }
            row.setDisputable(penaltyDisputeService.isDisputable(p, dispute != null));
            rows.add(row);
        }

        // Detected incidents the buffer period is still holding back — "may become a penalty".
        if (buffer > 0 && policy != null) {
            for (AttendanceException e : incidents) {
                if (!PENALIZABLE_TYPES.contains(e.getExceptionType())) continue;
                if (penalizedKeys.contains(e.getExceptionDate() + "|" + e.getExceptionType())) continue;
                LocalDate until = e.getExceptionDate().plusDays(buffer - 1L);
                if (today.isAfter(until)) continue;
                if (!couldBecomePenalty(userId, policy, e)) continue;
                rows.add(toBufferRow(e, attendanceByDate.get(e.getExceptionDate()), until,
                        openRegularizationDates.contains(e.getExceptionDate())));
            }
        }
        rows.sort(Comparator.comparing(MyPenaltyResponse::getIncidentDate).reversed()
                .thenComparing(MyPenaltyResponse::getRule));
        return MyPenaltiesResponse.builder().summary(summarize(userId, policy, buffer, today)).rows(rows).build();
    }

    // ── Summary tile ────────────────────────────────────────────────────────────────────────

    private MyPenaltiesResponse.Summary summarize(UUID userId, PenalizationPolicyVersion policy, int buffer, LocalDate today) {
        boolean lateRule = policy != null && policy.isLateArrivalEnabled();
        String period = policy != null && "WEEK".equals(policy.getLaExemptPeriod()) ? "WEEK" : "MONTH";
        LocalDate[] cycle = ExceptionService.cyclePeriod(today, period);
        int lateMarks = (int) attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(
                userId, cycle[0], cycle[1], "LATE");

        // Deduction totals follow the calendar month of "today" — what the coming payslip will carry.
        LocalDate monthFrom = today.withDayOfMonth(1);
        LocalDate monthTo = today.withDayOfMonth(today.lengthOfMonth());
        BigDecimal deduction = BigDecimal.ZERO;
        BigDecimal leave = BigDecimal.ZERO;
        BigDecimal lop = BigDecimal.ZERO;
        int active = 0;
        for (AttendancePenalty p : attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetween(userId, monthFrom, monthTo)) {
            if (!AttendancePenaltyStatus.PENDING_REVIEW.equals(p.getStatus())) continue;
            active++;
            deduction = deduction.add(nz(p.getDeductionDays()));
            leave = leave.add(nz(p.getLeaveDeductionDays()));
            lop = lop.add(nz(p.getLopDays()));
        }
        return MyPenaltiesResponse.Summary.builder()
                .period(period).periodFrom(cycle[0]).periodTo(cycle[1])
                .lateRuleEnabled(lateRule)
                .lateMarks(lateMarks)
                .lateMarksExempt(lateRule ? policy.getLaExemptCount() : null)
                .bufferPeriodDays(buffer)
                .activePenalties(active).deductionDays(deduction).leaveDays(leave).lopDays(lop)
                .build();
    }

    // ── Row building ────────────────────────────────────────────────────────────────────────

    private MyPenaltyResponse toRow(AttendancePenalty p, AttendanceException incident, Attendance attendance, boolean regOpen) {
        return MyPenaltyResponse.builder()
                .id(p.getId())
                .incidentDate(p.getIncidentDate())
                .discrepancyType(p.getDiscrepancyType())
                .rule(ruleLabel(p.getDiscrepancyType()))
                .details(details(p.getDiscrepancyType(), p.getIncidentDate(), incident, attendance))
                .status(p.getStatus())
                .deductionDays(p.getDeductionDays())
                .leaveDeductionDays(nz(p.getLeaveDeductionDays()))
                .lopDays(nz(p.getLopDays()))
                .leaveBreakdown(leaveSplit(p))
                .penalizedOn(p.getPenalizedOn())
                .correctable(false) // the buffer has elapsed by the time a penalty exists
                .regularizationOpen(regOpen)
                .cancellationReason(p.getCancellationReason())
                .build();
    }

    private MyPenaltyResponse toBufferRow(AttendanceException e, Attendance attendance, LocalDate until, boolean regOpen) {
        return MyPenaltyResponse.builder()
                .id(e.getId())
                .incidentDate(e.getExceptionDate())
                .discrepancyType(e.getExceptionType())
                .rule(ruleLabel(e.getExceptionType()))
                .details(details(e.getExceptionType(), e.getExceptionDate(), e, attendance))
                .status(IN_BUFFER)
                .leaveBreakdown(List.of())
                .correctableUntil(until)
                .correctable(!regOpen)
                .regularizationOpen(regOpen)
                .build();
    }

    /**
     * Would the engine penalize this incident once its buffer elapses? Mirrors only the cheap,
     * deterministic gates (rule enabled; late / missing-log count past the exempt count) so the
     * employee isn't warned about an incident that is simply exempt. Anything subtler (tiers,
     * ignore-when-hours-met, ...) stays the engine's call at evaluation time.
     */
    private boolean couldBecomePenalty(UUID userId, PenalizationPolicyVersion v, AttendanceException e) {
        switch (e.getExceptionType()) {
            case ExceptionType.LATE_ARRIVAL: {
                if (!v.isLateArrivalEnabled()) return false;
                if (v.getLaExemptCount() == null) return true;
                LocalDate[] c = ExceptionService.cyclePeriod(e.getExceptionDate(), v.getLaExemptPeriod());
                long upToThisOne = attendanceRepository
                        .countByEmployeeUserIdAndWorkDateBetweenAndStatus(userId, c[0], e.getExceptionDate(), "LATE");
                return upToThisOne > v.getLaExemptCount();
            }
            case ExceptionType.MISSING_PUNCH: {
                if (!v.isMissingLogsEnabled()) return false;
                if (v.getMlExemptDays() == null) return true;
                LocalDate[] c = ExceptionService.cyclePeriod(e.getExceptionDate(), v.getMlExemptPeriod());
                long upToThisOne = attendanceExceptionRepository
                        .countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                                userId, ExceptionType.MISSING_PUNCH, c[0], e.getExceptionDate());
                return upToThisOne > v.getMlExemptDays();
            }
            case ExceptionType.WORK_HOURS_SHORTAGE:
                return v.isWorkHoursShortageEnabled();
            case ExceptionType.EARLY_DEPARTURE: {
                // PN-05: minutesLate holds the minutes left before the shift end; only those past the
                // grace count toward the exempt count. An approved Partial Day request is the engine's call.
                if (!v.isEarlyGoingEnabled()) return false;
                int grace = v.getEgGracePeriodMinutes() != null ? v.getEgGracePeriodMinutes() : 0;
                if (e.getMinutesLate() == null || e.getMinutesLate() <= grace) return false;
                int exempt = v.getEgExemptCount() != null ? v.getEgExemptCount() : 0;
                LocalDate[] c = ExceptionService.cyclePeriod(e.getExceptionDate(), v.getEgExemptPeriod());
                long upToThisOne = attendanceExceptionRepository
                        .findByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                                userId, ExceptionType.EARLY_DEPARTURE, c[0], e.getExceptionDate())
                        .stream().filter(x -> x.getMinutesLate() != null && x.getMinutesLate() > grace).count();
                return upToThisOne > exempt;
            }
            case ExceptionType.EXCESS_BREAK: {
                if (!v.isExcessBreakEnabled()) return false;
                int exempt = v.getEbExemptCount() != null ? v.getEbExemptCount() : 0;
                LocalDate[] c = ExceptionService.cyclePeriod(e.getExceptionDate(), v.getEbExemptPeriod());
                long upToThisOne = attendanceExceptionRepository
                        .countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                                userId, ExceptionType.EXCESS_BREAK, c[0], e.getExceptionDate());
                return upToThisOne > exempt;
            }
            default:
                return false;
        }
    }

    static String ruleLabel(String discrepancyType) {
        if (discrepancyType == null) return "Attendance";
        return switch (discrepancyType) {
            case ExceptionType.LATE_ARRIVAL -> "Late Arrival";
            case ExceptionType.WORK_HOURS_SHORTAGE -> "Shortage";
            case ExceptionType.NO_ATTENDANCE -> "No Attendance";
            case ExceptionType.MISSING_PUNCH -> "Missing Logs";
            case ExceptionType.EARLY_DEPARTURE -> "Early Going";
            case ExceptionType.EXCESS_BREAK -> "Excess Break";
            default -> AttendancePenaltyEvaluationService.humanizeDiscrepancyType(discrepancyType);
        };
    }

    private String details(String type, LocalDate date, AttendanceException incident, Attendance attendance) {
        switch (type == null ? "" : type) {
            case ExceptionType.LATE_ARRIVAL: {
                LocalTime arrived = incident != null && incident.getActualTime() != null ? incident.getActualTime()
                        : attendance != null && attendance.getCheckInAt() != null ? attendance.getCheckInAt().toLocalTime() : null;
                LocalTime shiftStart = incident != null ? incident.getExpectedTime() : null;
                if (arrived == null) return "Late arrival recorded for this day.";
                Integer grace = graceMinutes(attendance, date);
                if (shiftStart != null && grace != null) {
                    return "Arrived " + arrived.format(TIME_FMT) + ", grace ended " + shiftStart.plusMinutes(grace).format(TIME_FMT);
                }
                return "Arrived " + arrived.format(TIME_FMT) + (shiftStart != null ? ", shift starts " + shiftStart.format(TIME_FMT) : "");
            }
            case ExceptionType.WORK_HOURS_SHORTAGE: {
                if (attendance != null && attendance.getWorkedMinutes() != null) {
                    return "Worked " + hm(attendance.getWorkedMinutes()) + ", below the hours required for the day";
                }
                return "Worked fewer hours than required for the day";
            }
            case ExceptionType.NO_ATTENDANCE:
                return "No attendance recorded on a working day";
            case ExceptionType.MISSING_PUNCH: {
                LocalTime in = incident != null && incident.getActualTime() != null ? incident.getActualTime()
                        : attendance != null && attendance.getCheckInAt() != null ? attendance.getCheckInAt().toLocalTime() : null;
                return in != null ? "Checked in " + in.format(TIME_FMT) + " but no check-out was recorded"
                        : "Check-in or check-out missing for this day";
            }
            case ExceptionType.EARLY_DEPARTURE: {
                LocalTime out = incident != null ? incident.getActualTime() : null;
                LocalTime end = incident != null ? incident.getExpectedTime() : null;
                return out != null && end != null ? "Left " + out.format(TIME_FMT) + ", shift ends " + end.format(TIME_FMT)
                        : "Left before the shift ended";
            }
            case ExceptionType.EXCESS_BREAK: {
                Integer over = incident != null ? incident.getMinutesLate() : null;
                return over != null && over > 0 ? "Breaks ran " + over + " min over the daily limit"
                        : "Break time went over the daily limit";
            }
            default:
                return "Attendance policy deduction";
        }
    }

    /** The late-grace of the shift snapshotted on that day's attendance — null when it can't be resolved. */
    private Integer graceMinutes(Attendance attendance, LocalDate date) {
        if (attendance == null || attendance.getShiftId() == null) return null;
        try {
            return shiftRepository.findById(attendance.getShiftId())
                    .flatMap(s -> shiftVersionResolver.resolveIfPresent(s, date))
                    .map(ShiftVersion::getLateGraceMinutes)
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.debug("MyPenaltyService: could not resolve shift grace for {}: {}", date, ex.getMessage());
            return null;
        }
    }

    private List<MyPenaltyResponse.LeaveSplit> leaveSplit(AttendancePenalty p) {
        if (p.getLeaveBreakdown() == null || p.getLeaveBreakdown().isBlank()) return List.of();
        try {
            Map<String, BigDecimal> map = objectMapper.readValue(p.getLeaveBreakdown(),
                    new TypeReference<LinkedHashMap<String, BigDecimal>>() {});
            List<MyPenaltyResponse.LeaveSplit> out = new ArrayList<>();
            map.forEach((code, days) -> out.add(MyPenaltyResponse.LeaveSplit.builder()
                    .leaveTypeCode(code)
                    .leaveTypeName(leaveTypeRepository.findByCode(code).map(t -> t.getName()).orElse(code))
                    .days(days).build()));
            return out;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String hm(int minutes) {
        return (minutes / 60) + "h " + String.format("%02d", minutes % 60) + "m";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}

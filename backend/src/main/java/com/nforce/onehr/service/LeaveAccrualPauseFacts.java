package com.nforce.onehr.service;

import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveTypeClassification;
import com.nforce.onehr.entity.PayrollLopCalculation;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.PayrollLopCalculationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static java.math.BigDecimal.ZERO;

/**
 * LV-03: the facts an accrual pause depends on, per employee and date range.
 * <ul>
 *   <li><b>Loss of pay</b>: payroll's LOP calculation ({@link PayrollLopCalculation}, the HR override when set) of
 *       every pay cycle that ends inside the range. A cycle payroll hasn't calculated yet counts as 0.</li>
 *   <li><b>Unpaid leave</b>: approved leave of an UNPAID-classified type, the days that fall inside the range
 *       (from the request's charged dates; for older requests, its days spread evenly over its calendar days).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LeaveAccrualPauseFacts {

    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollLopCalculationRepository lopRepository;

    /** Looked up only when a credit actually depends on them. */
    public interface Facts {
        BigDecimal lopDays(LocalDate from, LocalDate to);

        BigDecimal unpaidDays(LocalDate from, LocalDate to);

        Facts NONE = new Facts() {
            @Override public BigDecimal lopDays(LocalDate from, LocalDate to) { return ZERO; }
            @Override public BigDecimal unpaidDays(LocalDate from, LocalDate to) { return ZERO; }
        };
    }

    public Facts of(UUID employeeUserId) {
        return new Facts() {
            @Override
            public BigDecimal lopDays(LocalDate from, LocalDate to) {
                return lopRepository.findByEmployeeIdAndCycleEndBetween(employeeUserId, from, to).stream()
                        .map(c -> c.getOverrideLopDays() != null ? c.getOverrideLopDays() : c.getComputedLopDays())
                        .filter(java.util.Objects::nonNull)
                        .reduce(ZERO, BigDecimal::add);
            }

            @Override
            public BigDecimal unpaidDays(LocalDate from, LocalDate to) {
                List<LeaveRequest> requests = leaveRequestRepository.findActiveInRange(employeeUserId, List.of("APPROVED"), from, to);
                return requests.stream()
                        .filter(r -> r.getLeaveType() != null && LeaveTypeClassification.UNPAID.equals(r.getLeaveType().getClassification()))
                        .map(r -> daysWithin(r, from, to))
                        .reduce(ZERO, BigDecimal::add);
            }
        };
    }

    /** The days of {@code r} that fall on [from, to]. */
    static BigDecimal daysWithin(LeaveRequest r, LocalDate from, LocalDate to) {
        LeaveDayCounts counts = LeaveDayCounts.parse(r.getDayCounts());
        if (counts != null) {
            return counts.all().subMap(from, true, to, true).values().stream().reduce(ZERO, BigDecimal::add);
        }
        LocalDate start = r.getStartDate().isBefore(from) ? from : r.getStartDate();
        LocalDate end = r.getEndDate().isAfter(to) ? to : r.getEndDate();
        if (end.isBefore(start) || r.getTotalDays() == null) return ZERO;
        long span = ChronoUnit.DAYS.between(r.getStartDate(), r.getEndDate()) + 1;
        long inside = ChronoUnit.DAYS.between(start, end) + 1;
        if (inside >= span) return r.getTotalDays();
        return r.getTotalDays().multiply(BigDecimal.valueOf(inside)).divide(BigDecimal.valueOf(span), 2, RoundingMode.HALF_UP);
    }
}

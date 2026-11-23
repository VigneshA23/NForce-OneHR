package com.nforce.onehr.service;

import com.nforce.onehr.repository.LeaveBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared "how much paid leave does this employee have left" rule, used by both
 * {@link PayrollLopCalculationService} (US-C04: a no-attendance day only auto-counts as LOP when
 * this is zero) and {@link PayrollProrationService} (US-C07: leave encashment at exit). Pulled out
 * once two payroll services needed the identical computation, so the rule can't silently drift
 * between them.
 */
@Service
@RequiredArgsConstructor
public class LeaveBalanceAvailabilityService {

    private final LeaveBalanceRepository leaveBalanceRepository;

    /**
     * Unused days across every regular paid {@link com.nforce.onehr.entity.LeaveType} balance for the
     * given leave year: current-year (totalDays - usedDays) plus carried-forward still unused and
     * unexpired. Per-occurrence (Maternity) and unpaid types are not a general paid balance.
     */
    public BigDecimal availablePaidDays(UUID employeeId, int year) {
        return leaveBalanceRepository.findByEmployeeUserIdAndYear(employeeId, year).stream()
                .filter(b -> b.getLeaveType().isAnnualBalance())
                .map(b -> b.getTotalDays().subtract(b.getUsedDays()).add(LeaveBalanceService.carriedForwardAvailable(b)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

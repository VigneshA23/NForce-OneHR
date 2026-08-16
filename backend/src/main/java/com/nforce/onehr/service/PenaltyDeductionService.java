package com.nforce.onehr.service;

import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Section 7/48: resolves one {@link AttendancePenalty}'s configured deduction-days amount into an
 * actual leave-balance debit and/or Loss-of-Pay amount, mutating the (not-yet-persisted) penalty
 * row in place — {@link AttendancePenaltyEvaluationService} saves it once, already carrying the
 * deduction outcome. Reuses {@link LeaveBalanceRepository}'s existing
 * {@code findByEmployeeUserIdAndLeaveTypeIdAndYear}/save pattern (the same one
 * {@code LeaveService.approve} uses) rather than inventing a second balance-mutation path.
 *
 * <p>{@code LOSS_OF_PAY} is the simple case: the whole amount becomes {@code lopDays}, nothing is
 * deducted from any leave balance. {@code PAID_LEAVE} cascades across the version's configured
 * {@code leavePriorityOrder} (comma-separated {@link LeaveType#getCode()} values) in order,
 * debiting each balance's {@code usedDays} up to what's available; whatever remains once every
 * configured leave type is exhausted (or none is configured) becomes {@code lopDays} — the exact
 * "Available Leave + Remaining Penalty -> Remaining amount becomes Loss of Pay" fallback the
 * requirements describe.
 */
@Service
@RequiredArgsConstructor
public class PenaltyDeductionService {

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final AuditSnapshotSerializer snapshotSerializer;

    public void apply(AttendancePenalty penalty, String deductionMethod, String leavePriorityOrder) {
        BigDecimal totalDays = penalty.getDeductionDays();
        if (totalDays == null || totalDays.signum() <= 0) {
            penalty.setDeductionMethod(deductionMethod);
            penalty.setLeaveDeductionDays(BigDecimal.ZERO);
            penalty.setLopDays(BigDecimal.ZERO);
            return;
        }

        if (!"PAID_LEAVE".equals(deductionMethod)) {
            penalty.setDeductionMethod("LOSS_OF_PAY");
            penalty.setLeaveDeductionDays(BigDecimal.ZERO);
            penalty.setLopDays(totalDays);
            return;
        }

        int year = penalty.getIncidentDate().getYear();
        BigDecimal remaining = totalDays;
        Map<String, BigDecimal> breakdown = new LinkedHashMap<>();

        for (String code : parseLeaveTypeCodes(leavePriorityOrder)) {
            if (remaining.signum() <= 0) {
                break;
            }
            LeaveType type = leaveTypeRepository.findByCode(code).orElse(null);
            if (type == null) {
                continue;
            }
            LeaveBalance balance = leaveBalanceRepository
                    .findByEmployeeUserIdAndLeaveTypeIdAndYear(penalty.getEmployeeUserId(), type.getId(), year)
                    .orElse(null);
            if (balance == null) {
                continue;
            }
            BigDecimal available = balance.getTotalDays().subtract(balance.getUsedDays());
            if (available.signum() <= 0) {
                continue;
            }
            BigDecimal deduct = available.min(remaining);
            balance.setUsedDays(balance.getUsedDays().add(deduct));
            leaveBalanceRepository.save(balance);
            breakdown.merge(code, deduct, BigDecimal::add);
            remaining = remaining.subtract(deduct);
        }

        penalty.setDeductionMethod("PAID_LEAVE");
        penalty.setLeaveDeductionDays(totalDays.subtract(remaining));
        penalty.setLopDays(remaining);
        penalty.setLeaveBreakdown(breakdown.isEmpty() ? null : snapshotSerializer.toJson(new LinkedHashMap<String, Object>(breakdown)));
    }

    private List<String> parseLeaveTypeCodes(String leavePriorityOrder) {
        if (leavePriorityOrder == null || leavePriorityOrder.isBlank()) {
            return List.of();
        }
        return List.of(leavePriorityOrder.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

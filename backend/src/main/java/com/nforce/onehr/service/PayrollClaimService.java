package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayrollClaimResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ExpenseCategory;
import com.nforce.onehr.entity.ExpenseClaim;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ExpenseCategoryRepository;
import com.nforce.onehr.repository.ExpenseClaimRepository;
import com.nforce.onehr.repository.PayslipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Keka's "Salary Component Claim"/"Expenses" steps — visibility only. Payroll's own
 * {@code REIMBURSEMENT}-type structure components and the Assets & Expenses epic's
 * {@code ExpenseClaim} are genuinely two separate, unlinked systems today: a cleared claim never
 * becomes a {@code PayslipLineItem}, and nothing here changes that — actually wiring one into the
 * other is a real, unconfirmed business-rule decision (which component, rounding, timing) nobody's
 * made yet. This reports what Rohit described HR already does in the Expenses module itself
 * (choosing "with payroll" vs "outside payroll" per claim at approval time) — i.e. whatever landed
 * in {@code CLEARED_FOR_PAYROLL} for this run's own employees.
 */
@Service
@RequiredArgsConstructor
public class PayrollClaimService {

    private static final String CLEARED_FOR_PAYROLL = "CLEARED_FOR_PAYROLL";

    private final PayslipRepository payslipRepository;
    private final ExpenseClaimRepository expenseClaimRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<PayrollClaimResponse> listForRun(UUID runId) {
        List<UUID> employeeIds = payslipRepository.findByPayrollRunId(runId).stream().map(Payslip::getEmployeeId).toList();
        if (employeeIds.isEmpty()) {
            return List.of();
        }

        List<ExpenseClaim> claims = expenseClaimRepository.findByEmployeeUserIdInAndStatus(employeeIds, CLEARED_FOR_PAYROLL);

        Map<UUID, String> nameByEmployeeId = employeeRepository.findAllByIdWithDepartment(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Employee::getFullName));
        Map<Integer, String> categoryNameById = expenseCategoryRepository
                .findAllById(claims.stream().map(ExpenseClaim::getCategoryId).distinct().toList()).stream()
                .collect(Collectors.toMap(ExpenseCategory::getId, ExpenseCategory::getName));

        return claims.stream()
                .map(c -> new PayrollClaimResponse(c.getId(), c.getEmployeeUserId(),
                        nameByEmployeeId.getOrDefault(c.getEmployeeUserId(), "Unknown"),
                        categoryNameById.getOrDefault(c.getCategoryId(), "Unknown"),
                        c.getAmount(), c.getExpenseDate(), c.getStatus()))
                .toList();
    }
}

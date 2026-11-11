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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. */
@ExtendWith(MockitoExtension.class)
class PayrollClaimServiceTest {

    @Mock private PayslipRepository payslipRepository;
    @Mock private ExpenseClaimRepository expenseClaimRepository;
    @Mock private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock private EmployeeRepository employeeRepository;

    @InjectMocks private PayrollClaimService service;

    private final UUID runId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @Test
    void listForRun_noPayslips_returnsEmptyListWithoutQueryingClaims() {
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of());

        List<PayrollClaimResponse> result = service.listForRun(runId);

        assertTrue(result.isEmpty());
        verifyNoInteractions(expenseClaimRepository);
    }

    @Test
    void listForRun_onlyIncludesClearedForPayrollClaims_withResolvedNames() {
        Payslip payslip = Payslip.builder().id(UUID.randomUUID()).employeeId(employeeId).build();
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(payslip));

        ExpenseClaim claim = ExpenseClaim.builder().id(UUID.randomUUID()).employeeUserId(employeeId).categoryId(5)
                .amount(new BigDecimal("1200.00")).expenseDate(LocalDate.of(2026, 6, 10)).status("CLEARED_FOR_PAYROLL").build();
        when(expenseClaimRepository.findByEmployeeUserIdInAndStatus(List.of(employeeId), "CLEARED_FOR_PAYROLL")).thenReturn(List.of(claim));

        Employee employee = Employee.builder().userId(employeeId).fullName("Chandu Shyam").build();
        when(employeeRepository.findAllByIdWithDepartment(List.of(employeeId))).thenReturn(List.of(employee));

        ExpenseCategory category = ExpenseCategory.builder().id(5).name("Travel").build();
        when(expenseCategoryRepository.findAllById(List.of(5))).thenReturn(List.of(category));

        List<PayrollClaimResponse> result = service.listForRun(runId);

        assertEquals(1, result.size());
        PayrollClaimResponse resp = result.get(0);
        assertEquals(claim.getId(), resp.id());
        assertEquals(employeeId, resp.employeeId());
        assertEquals("Chandu Shyam", resp.employeeName());
        assertEquals("Travel", resp.categoryName());
        assertEquals(0, new BigDecimal("1200.00").compareTo(resp.amount()));
        assertEquals("CLEARED_FOR_PAYROLL", resp.status());
    }

    @Test
    void listForRun_unknownEmployeeOrCategory_fallsBackToUnknown() {
        Payslip payslip = Payslip.builder().id(UUID.randomUUID()).employeeId(employeeId).build();
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(payslip));

        ExpenseClaim claim = ExpenseClaim.builder().id(UUID.randomUUID()).employeeUserId(employeeId).categoryId(99)
                .amount(BigDecimal.TEN).expenseDate(LocalDate.now()).status("CLEARED_FOR_PAYROLL").build();
        when(expenseClaimRepository.findByEmployeeUserIdInAndStatus(List.of(employeeId), "CLEARED_FOR_PAYROLL")).thenReturn(List.of(claim));
        when(employeeRepository.findAllByIdWithDepartment(List.of(employeeId))).thenReturn(List.of());
        when(expenseCategoryRepository.findAllById(List.of(99))).thenReturn(List.of());

        List<PayrollClaimResponse> result = service.listForRun(runId);

        assertEquals("Unknown", result.get(0).employeeName());
        assertEquals("Unknown", result.get(0).categoryName());
    }
}

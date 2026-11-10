package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.YtdResponse;
import com.nforce.onehr.dto.payroll.YtdResponse.Component;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayslipLineItemRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * US-C17 — Year-to-Date earnings/deductions for ONE employee over the current fiscal year.
 *
 * <p>Same aggregation idea as the run-level reconciliation ({@code sumAmountByComponentType}), but
 * scoped to one employee and a fiscal-year window. Only payslips of APPROVED/DISBURSED runs count,
 * enforced in the query itself, and a payslip belongs to the fiscal year its pay cycle ENDS in (so a
 * 26 Mar – 25 Apr cycle is the first month of an April-start year).
 *
 * <p>Two paths, deliberately separate: {@link #getMyYtd} resolves the employee from the caller's
 * identity only (never a parameter — the MyFinances convention), while {@link #getYtdFor} takes an
 * explicit employeeId and is reachable only through the payroll:VIEW:ALL-gated HR endpoint.
 *
 * <p>The fiscal-year start month is {@code app.payroll.fiscal-year-start-month} (default 4, April).
 * UNCONFIRMED for this org (Decision #13); changing the config value is the whole migration.
 */
@Service
public class PayrollYtdService {

    private static final Set<String> EARNING_TYPES = Set.of(
            SalaryComponentType.EARNING, SalaryComponentType.AD_HOC_EARNING, SalaryComponentType.REIMBURSEMENT);
    private static final Set<String> DEDUCTION_TYPES = Set.of(
            SalaryComponentType.DEDUCTION, SalaryComponentType.AD_HOC_DEDUCTION);

    private final PayslipRepository payslipRepository;
    private final PayslipLineItemRepository lineItemRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final int fiscalYearStartMonth;

    public PayrollYtdService(PayslipRepository payslipRepository, PayslipLineItemRepository lineItemRepository,
                             EmployeeRepository employeeRepository, UserRepository userRepository, Clock clock,
                             @Value("${app.payroll.fiscal-year-start-month:4}") int fiscalYearStartMonth) {
        if (fiscalYearStartMonth < 1 || fiscalYearStartMonth > 12) {
            throw new IllegalArgumentException(
                    "app.payroll.fiscal-year-start-month must be between 1 and 12, was " + fiscalYearStartMonth);
        }
        this.payslipRepository = payslipRepository;
        this.lineItemRepository = lineItemRepository;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.fiscalYearStartMonth = fiscalYearStartMonth;
    }

    /** The caller's own YTD — resolved from the JWT principal, never from a request parameter. */
    @Transactional(readOnly = true)
    public YtdResponse getMyYtd(String actorEmail) {
        UUID employeeId = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found")).getId();
        return ytd(employeeId, LocalDate.now(clock));
    }

    /** Any employee's YTD — HR path; the controller gates this behind payroll:VIEW:ALL. */
    @Transactional(readOnly = true)
    public YtdResponse getYtdFor(UUID employeeId) {
        if (!employeeRepository.existsById(employeeId)) {
            throw new NoSuchElementException("Employee not found");
        }
        return ytd(employeeId, LocalDate.now(clock));
    }

    YtdResponse ytd(UUID employeeId, LocalDate today) {
        FiscalYear fy = FiscalYear.containing(today, fiscalYearStartMonth);
        Object[] totals = payslipRepository
                .summarizeForEmployee(employeeId, fy.start(), fy.end(), PayslipService.VISIBLE_RUN_STATUSES).get(0);
        List<Object[]> componentRows = lineItemRepository
                .sumByComponentForEmployee(employeeId, fy.start(), fy.end(), PayslipService.VISIBLE_RUN_STATUSES);

        return new YtdResponse(employeeId, fy.label(), fy.start(), fy.end(),
                ((Number) totals[0]).intValue(), money(totals[1]), money(totals[2]), money(totals[3]), money(totals[4]),
                components(componentRows, EARNING_TYPES), components(componentRows, DEDUCTION_TYPES));
    }

    /** Only earning/deduction types are listed; anything else (e.g. a contribution) is never shown. */
    private static List<Component> components(List<Object[]> rows, Set<String> types) {
        return rows.stream()
                .filter(r -> types.contains((String) r[1]))
                .map(r -> new Component((String) r[0], (BigDecimal) r[2]))
                .sorted(Comparator.comparing(Component::name))
                .toList();
    }

    /** SUM over no rows is SQL NULL; an employee with no payslips yet has zero YTD, not an error. */
    private static BigDecimal money(Object sum) {
        return sum == null ? BigDecimal.ZERO : (BigDecimal) sum;
    }
}

package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayrollJoinersExitsResponse;
import com.nforce.onehr.dto.payroll.PayrollJoinersExitsResponse.EmployeeDateEntry;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayrollRunRepository;
import com.nforce.onehr.repository.PayslipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Keka's "New Joinees & Exits" step — see PayrollJoinersExitsResponse's own Javadoc. Same
 * employee-scoping convention as PayrollCostReportService: this run's own payslips, not every
 * employee system-wide. */
@Service
@RequiredArgsConstructor
public class PayrollJoinersExitsService {

    private final PayrollRunRepository payrollRunRepository;
    private final PayslipRepository payslipRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public PayrollJoinersExitsResponse build(UUID runId) {
        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Payroll run not found"));

        List<UUID> employeeIds = payslipRepository.findByPayrollRunId(runId).stream().map(Payslip::getEmployeeId).toList();

        List<EmployeeDateEntry> joiners = employeeRepository.findJoinersInRange(employeeIds, run.getCycleStart(), run.getCycleEnd())
                .stream().map(e -> toEntry(e, e.getJoiningDate()))
                .sorted(Comparator.comparing(EmployeeDateEntry::date))
                .toList();

        List<EmployeeDateEntry> exits = employeeRepository.findExitsInRange(employeeIds, run.getCycleStart(), run.getCycleEnd())
                .stream().map(e -> toEntry(e, e.getLastWorkingDay()))
                .sorted(Comparator.comparing(EmployeeDateEntry::date))
                .toList();

        return new PayrollJoinersExitsResponse(runId, run.getCycleStart(), run.getCycleEnd(), joiners, exits);
    }

    private static EmployeeDateEntry toEntry(Employee employee, java.time.LocalDate date) {
        return new EmployeeDateEntry(employee.getUserId(), employee.getFullName(), date);
    }
}

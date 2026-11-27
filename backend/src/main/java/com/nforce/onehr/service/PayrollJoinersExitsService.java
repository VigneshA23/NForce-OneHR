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
    private final LeaveExitService leaveExitService;

    @Transactional(readOnly = true)
    public PayrollJoinersExitsResponse build(UUID runId) {
        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Payroll run not found"));

        List<UUID> employeeIds = payslipRepository.findByPayrollRunId(runId).stream().map(Payslip::getEmployeeId).toList();

        List<EmployeeDateEntry> joiners = employeeRepository.findJoinersInRange(employeeIds, run.getCycleStart(), run.getCycleEnd())
                .stream().map(e -> toEntry(e, e.getJoiningDate()))
                .sorted(Comparator.comparing(EmployeeDateEntry::date))
                .toList();

        List<com.nforce.onehr.entity.Employee> leavers = employeeRepository.findExitsInRange(employeeIds, run.getCycleStart(), run.getCycleEnd());
        // LV-04: each leaver's leave position as last computed (nightly, or when the exit cycle is prorated).
        java.util.Map<UUID, List<com.nforce.onehr.entity.LeaveExitSettlement>> settlements =
                leaveExitService.stored(leavers.stream().map(com.nforce.onehr.entity.Employee::getUserId).toList());
        List<EmployeeDateEntry> exits = leavers.stream()
                .map(e -> {
                    List<com.nforce.onehr.entity.LeaveExitSettlement> s = settlements.get(e.getUserId());
                    if (s == null || s.isEmpty()) return toEntry(e, e.getLastWorkingDay());
                    return new EmployeeDateEntry(e.getUserId(), e.getFullName(), e.getLastWorkingDay(),
                            s.stream().map(com.nforce.onehr.entity.LeaveExitSettlement::getEncashableDays).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add),
                            s.stream().map(com.nforce.onehr.entity.LeaveExitSettlement::getRecoveryDays).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
                })
                .sorted(Comparator.comparing(EmployeeDateEntry::date))
                .toList();

        return new PayrollJoinersExitsResponse(runId, run.getCycleStart(), run.getCycleEnd(), joiners, exits);
    }

    private static EmployeeDateEntry toEntry(Employee employee, java.time.LocalDate date) {
        return new EmployeeDateEntry(employee.getUserId(), employee.getFullName(), date);
    }
}

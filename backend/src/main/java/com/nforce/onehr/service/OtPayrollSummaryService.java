package com.nforce.onehr.service;

import com.nforce.onehr.dto.overtime.OtDtos.PayrollOtEmployee;
import com.nforce.onehr.dto.overtime.OtDtos.PayrollOtRow;
import com.nforce.onehr.dto.overtime.OtDtos.PayrollOtSummary;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.OtCalculation;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.entity.PayslipLineItem;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.OtCalculationRepository;
import com.nforce.onehr.repository.PayrollRunRepository;
import com.nforce.onehr.repository.PayslipLineItemRepository;
import com.nforce.onehr.repository.PayslipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Employee-wise OT for a payroll run, with approved-OT vs payslip-OT discrepancy flags so HR can
 * validate before the run is finalized.
 */
@Service
@RequiredArgsConstructor
public class OtPayrollSummaryService {

    private final PayrollRunRepository runRepository;
    private final PayslipRepository payslipRepository;
    private final PayslipLineItemRepository lineItemRepository;
    private final OtCalculationRepository calculationRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public PayrollOtSummary summary(UUID runId) {
        PayrollRun run = runRepository.findById(runId).orElseThrow(() -> new IllegalArgumentException("Payroll run not found"));

        // OT payslip amounts per employee (line items named like the OT catalog component).
        Map<UUID, BigDecimal> payslipOt = new LinkedHashMap<>();
        for (Payslip p : payslipRepository.findByPayrollRunId(runId)) {
            BigDecimal sum = lineItemRepository.findByPayslipId(p.getId()).stream()
                    .filter(li -> PayrollRunService.OT_COMPONENT_NAME.equalsIgnoreCase(li.getComponentName()))
                    .map(PayslipLineItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            payslipOt.put(p.getEmployeeId(), sum);
        }

        List<OtCalculation> approved = calculationRepository.findApprovedInRange(run.getCycleStart(), run.getCycleEnd());
        Map<UUID, List<OtCalculation>> byEmployee = approved.stream()
                .collect(Collectors.groupingBy(OtCalculation::getEmployeeUserId));

        Set<UUID> ids = new HashSet<>(byEmployee.keySet());
        payslipOt.forEach((id, amt) -> { if (amt.signum() != 0) ids.add(id); });
        Map<UUID, Employee> employees = ids.isEmpty() ? Map.of()
                : employeeRepository.findAllByIdWithDepartment(ids).stream()
                        .collect(Collectors.toMap(Employee::getUserId, e -> e));

        List<PayrollOtEmployee> out = new ArrayList<>();
        BigDecimal transferred = BigDecimal.ZERO;
        BigDecimal approvedTotal = BigDecimal.ZERO;
        for (UUID id : ids) {
            Employee e = employees.get(id);
            List<OtCalculation> calcs = byEmployee.getOrDefault(id, List.of());
            BigDecimal approvedAmount = calcs.stream().map(OtCalculation::getOtAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            BigDecimal paid = payslipOt.getOrDefault(id, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            List<PayrollOtRow> rows = calcs.stream().sorted(Comparator.comparing(OtCalculation::getWorkDate))
                    .map(c -> new PayrollOtRow(id, e != null ? e.getEmployeeCode() : null,
                            e != null ? e.getFullName() : "Unknown", c.getWorkDate(),
                            BigDecimal.valueOf(c.getOtMinutes()).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP),
                            c.getHourlyRate(), c.getMultiplier(), c.getOtAmount())).toList();
            out.add(new PayrollOtEmployee(id, e != null ? e.getEmployeeCode() : null,
                    e != null ? e.getFullName() : "Unknown", approvedAmount, paid,
                    approvedAmount.compareTo(paid) != 0, rows));
            transferred = transferred.add(paid);
            approvedTotal = approvedTotal.add(approvedAmount);
        }
        out.sort(Comparator.comparing(PayrollOtEmployee::employeeName, String.CASE_INSENSITIVE_ORDER));
        return new PayrollOtSummary(runId, run.getCycleStart(), run.getCycleEnd(), transferred, approvedTotal, out);
    }
}

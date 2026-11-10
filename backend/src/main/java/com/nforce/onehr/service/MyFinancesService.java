package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.EmployeeCompensationResponse;
import com.nforce.onehr.dto.payroll.MyCtcBreakdownResponse;
import com.nforce.onehr.dto.payroll.MyCtcComponentResponse;
import com.nforce.onehr.entity.EmployeeCompensation;
import com.nforce.onehr.entity.SalaryStructureComponent;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C33 (ONEHR-579) slice — "My Finances": the one part of this batch buildable without US-C12
 * (payslip PDF) or US-C21/C15 (statutory info, bank details), which the rest of the story needs
 * and this codebase doesn't have yet. No special authority on either endpoint — any authenticated
 * employee sees only their OWN data, resolved from the JWT principal, never from a path/query
 * employeeId (the exact mistake that would leak one employee's CTC to another).
 *
 * <p>The "salary revision timeline" half is already fully served by {@link
 * EmployeeCompensationService#getHistory} — this just wraps it with the "resolve my own
 * employeeId" step. The "current CTC breakdown" half reuses {@link
 * SalaryStructureResolutionService#resolveAnnualValues} — the exact same engine {@code
 * PayrollRunService#buildPayslip} uses for a real payslip — so this preview can never drift from
 * what an actual payroll run would compute.
 */
@Service
@RequiredArgsConstructor
public class MyFinancesService {

    private static final int MONTHS_PER_YEAR = 12;

    private final EmployeeCompensationRepository employeeCompensationRepository;
    private final EmployeeCompensationService employeeCompensationService;
    private final SalaryStructureComponentRepository salaryStructureComponentRepository;
    private final SalaryStructureResolutionService salaryStructureResolutionService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public MyCtcBreakdownResponse getCurrentCtcBreakdown(String actorEmail) {
        UUID employeeId = requireEmployeeId(actorEmail);
        EmployeeCompensation current = employeeCompensationRepository.findByEmployeeIdAndEffectiveToIsNull(employeeId)
                .orElseThrow(() -> new NoSuchElementException("No current compensation on file"));

        List<SalaryStructureComponent> components = salaryStructureComponentRepository.findByStructureId(current.getStructure().getId());
        Map<UUID, BigDecimal> annualByComponentId = salaryStructureResolutionService.resolveAnnualValues(components, current.getCtc());

        List<MyCtcComponentResponse> componentResponses = components.stream()
                .map(sc -> {
                    BigDecimal annual = annualByComponentId.get(sc.getId()).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal monthly = annual.divide(BigDecimal.valueOf(MONTHS_PER_YEAR), 2, RoundingMode.HALF_UP);
                    return MyCtcComponentResponse.builder()
                            .componentName(sc.getComponent().getName())
                            .componentType(sc.getComponent().getType())
                            .annualAmount(annual)
                            .monthlyAmount(monthly)
                            .build();
                })
                .collect(Collectors.toList());

        return MyCtcBreakdownResponse.builder()
                .structureId(current.getStructure().getId())
                .structureName(current.getStructure().getName())
                .ctc(current.getCtc())
                .effectiveFrom(current.getEffectiveFrom())
                .components(componentResponses)
                .build();
    }

    @Transactional(readOnly = true)
    public List<EmployeeCompensationResponse> getCompensationHistory(String actorEmail) {
        return employeeCompensationService.getHistory(requireEmployeeId(actorEmail));
    }

    private UUID requireEmployeeId(String email) {
        User actor = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        return actor.getId();
    }
}

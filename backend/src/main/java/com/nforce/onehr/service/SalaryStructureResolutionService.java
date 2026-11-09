package com.nforce.onehr.service;

import com.nforce.onehr.entity.SalaryCalculationType;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.SalaryStructureComponent;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Shared component-resolution engine, extracted from {@code PayrollRunService} so US-C33's
 * "current CTC breakdown" (a standalone projection, no payroll run involved) and
 * {@code PayrollRunService#buildPayslip}'s real payslip computation can never drift apart — both
 * call this exact same method rather than each reconstructing the resolution order.
 *
 * <p>FIXED → PERCENT_OF_COMPONENT (iterative, cycle-safe) → PERCENT_OF_GROSS →
 * BALANCING_REMAINDER. Everything resolved here is an ANNUAL figure — see
 * {@code PayrollRunService}'s own class Javadoc for why (structure component values are annual,
 * confirmed against a real Keka "Update Annual salary structure" screenshot).
 */
@Service
public class SalaryStructureResolutionService {

    private static final Set<String> EARNING_TYPES = Set.of(
            SalaryComponentType.EARNING, SalaryComponentType.AD_HOC_EARNING, SalaryComponentType.REIMBURSEMENT);

    public Map<UUID, BigDecimal> resolveAnnualValues(List<SalaryStructureComponent> components, BigDecimal ctcAnnual) {
        Map<UUID, BigDecimal> resolved = new HashMap<>();
        List<SalaryStructureComponent> percentOfComponent = new ArrayList<>();
        SalaryStructureComponent balancingRemainder = null;

        for (SalaryStructureComponent sc : components) {
            switch (sc.getCalcType()) {
                case SalaryCalculationType.FIXED -> resolved.put(sc.getId(), sc.getValue());
                case SalaryCalculationType.PERCENT_OF_COMPONENT -> percentOfComponent.add(sc);
                case SalaryCalculationType.PERCENT_OF_GROSS -> resolved.put(sc.getId(),
                        sc.getValue().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).multiply(ctcAnnual));
                case SalaryCalculationType.BALANCING_REMAINDER -> balancingRemainder = sc;
                default -> throw new IllegalStateException("Unknown calcType: " + sc.getCalcType());
            }
        }

        int remaining = percentOfComponent.size();
        while (remaining > 0) {
            boolean progressed = false;
            for (SalaryStructureComponent sc : percentOfComponent) {
                if (resolved.containsKey(sc.getId())) continue;
                BigDecimal referenceValue = resolved.get(sc.getReferenceStructureComponentId());
                if (referenceValue != null) {
                    resolved.put(sc.getId(), sc.getValue().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).multiply(referenceValue));
                    remaining--;
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalStateException("Could not resolve PERCENT_OF_COMPONENT reference chain — circular reference in structure");
            }
        }

        if (balancingRemainder != null) {
            final SalaryStructureComponent remainder = balancingRemainder;
            BigDecimal sumOfOthers = components.stream()
                    .filter(sc -> sc != remainder && EARNING_TYPES.contains(sc.getComponent().getType()))
                    .map(sc -> resolved.get(sc.getId()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            resolved.put(remainder.getId(), ctcAnnual.subtract(sumOfOthers));
        }

        return resolved;
    }
}

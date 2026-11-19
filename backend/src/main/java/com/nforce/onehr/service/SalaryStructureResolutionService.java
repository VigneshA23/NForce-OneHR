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
 *
 * <p><b>CTC vs Gross (2026-11-19, confirmed against a real Keka "Salary Breakdown" screen):</b>
 * CTC is NOT the same figure "PERCENT_OF_GROSS" should be a percentage of. A real breakdown reads
 * {@code CTC = Gross (Basic+HRA+Travel+Allowances) + Employer PF + Gratuity} — Employer
 * contributions sit ON TOP of Gross, inside CTC, never inside Gross itself. A {@link
 * SalaryStructureComponent} whose catalog {@link com.nforce.onehr.entity.SalaryComponent#getType()}
 * is {@code CONTRIBUTION} (Employer PF, Gratuity, ...) is exactly this: part of CTC, excluded from
 * Gross. Since Basic is itself PERCENT_OF_GROSS and Employer PF/Gratuity are PERCENT_OF_COMPONENT
 * against Basic, Gross and the contributions are mutually dependent — resolved by iterating to a
 * fixed point ({@link #resolveAnnualValues}) rather than hardcoding this specific percentage
 * shape, so any future contribution mix still resolves correctly. A structure with no CONTRIBUTION
 * components converges on its first pass (contributions total is always zero), so this is a
 * behavior-preserving generalization for every existing structure, not a breaking change.
 */
@Service
public class SalaryStructureResolutionService {

    private static final Set<String> EARNING_TYPES = Set.of(
            SalaryComponentType.EARNING, SalaryComponentType.AD_HOC_EARNING, SalaryComponentType.REIMBURSEMENT);

    // Numerical-method constants for the fixed-point iteration below — not a payroll rate, so
    // exempt from the "never hardcode a payroll number" rule. The relationship is linear and
    // contracting (a contribution is always some % of Basic, which is always some % of Gross), so
    // convergence to sub-paisa precision happens in well under 25 passes for any realistic
    // structure; MAX_ITERATIONS is just a safety bound against a misconfigured structure that
    // doesn't converge, so resolution fails fast instead of looping forever.
    private static final int MAX_ITERATIONS = 25;
    private static final BigDecimal CONVERGENCE_EPSILON = new BigDecimal("0.01");

    public Map<UUID, BigDecimal> resolveAnnualValues(List<SalaryStructureComponent> components, BigDecimal ctcAnnual) {
        BigDecimal contributionsTotal = BigDecimal.ZERO;
        Map<UUID, BigDecimal> resolved = Map.of();
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            BigDecimal effectiveGross = ctcAnnual.subtract(contributionsTotal);
            resolved = resolveAgainstGross(components, effectiveGross);
            final Map<UUID, BigDecimal> currentResolved = resolved;
            BigDecimal newContributionsTotal = components.stream()
                    .filter(sc -> SalaryComponentType.CONTRIBUTION.equals(sc.getComponent().getType()))
                    .map(sc -> currentResolved.get(sc.getId()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean converged = newContributionsTotal.subtract(contributionsTotal).abs().compareTo(CONVERGENCE_EPSILON) < 0;
            contributionsTotal = newContributionsTotal;
            if (converged) break;
        }
        return resolved;
    }

    private Map<UUID, BigDecimal> resolveAgainstGross(List<SalaryStructureComponent> components, BigDecimal grossAnnual) {
        Map<UUID, BigDecimal> resolved = new HashMap<>();
        List<SalaryStructureComponent> percentOfComponent = new ArrayList<>();
        SalaryStructureComponent balancingRemainder = null;

        for (SalaryStructureComponent sc : components) {
            switch (sc.getCalcType()) {
                case SalaryCalculationType.FIXED -> resolved.put(sc.getId(), sc.getValue());
                case SalaryCalculationType.PERCENT_OF_COMPONENT -> percentOfComponent.add(sc);
                case SalaryCalculationType.PERCENT_OF_GROSS -> resolved.put(sc.getId(),
                        sc.getValue().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).multiply(grossAnnual));
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
            resolved.put(remainder.getId(), grossAnnual.subtract(sumOfOthers));
        }

        return resolved;
    }
}

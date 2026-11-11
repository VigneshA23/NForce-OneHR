package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.StatutoryDeductionOverride;
import com.nforce.onehr.entity.StatutoryRule;
import com.nforce.onehr.repository.SalaryComponentRepository;
import com.nforce.onehr.repository.StatutoryDeductionOverrideRepository;
import com.nforce.onehr.repository.StatutoryRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * US-C13, Phase 3 — the computation {@link StatutoryRule} (Phase 2) existed to eventually feed.
 * Reads whichever rule version was effective for the payslip's own cycle, reads the matching
 * catalog entry by the reserved name each rule type expects, computes, and hands back plain
 * (componentName, amount) pairs for {@code PayrollRunService#buildPayslip} to turn into
 * {@link com.nforce.onehr.entity.PayslipLineItem}s — same shape as that method's own US-C24
 * ad-hoc-payment loop, which already builds a line item straight from a source row's own
 * componentId/name/type/amount instead of going through {@link SalaryStructureResolutionService}.
 *
 * <p><b>Silent-skip, not fail, on anything unconfigured</b> — deliberately different from
 * {@code AdHocPaymentService}/{@code AdHocDeductionService}, which throw when an HR Admin typed a
 * component name expecting it to exist. Nobody explicitly asks for "PF" on a given run; it's
 * computed automatically on every {@code generateDraft} call, so a tenant that hasn't set up PF
 * yet (no catalog component, no {@link StatutoryRule}, or no {@code Location} on the employee at
 * all) must still generate payroll — just without that line item, exactly like today's behavior
 * before this phase existed.
 *
 * <p>Scope: PF and Professional Tax use confirmed real formulas (see the project's payroll
 * business-rules notes); ESI uses an industry-standard default (0.75% employee, ₹21,000 monthly
 * eligibility ceiling) flagged as a best-effort default, not yet HR-confirmed — same as every
 * number here, it's correctable through the Statutory Rules admin screen, never a redeploy.
 * Gratuity (employer-side accrual, never a payslip line item) and TDS (needs full annual
 * income-tax slab computation) are explicitly out of scope for this phase.
 */
@Service
@RequiredArgsConstructor
public class StatutoryDeductionService {

    private static final String PF = "PF";
    private static final String ESI = "ESI";
    private static final String PROFESSIONAL_TAX = "PROFESSIONAL_TAX";

    private final StatutoryRuleRepository statutoryRuleRepository;
    private final StatutoryDeductionOverrideRepository statutoryDeductionOverrideRepository;
    private final SalaryComponentRepository salaryComponentRepository;
    private final ObjectMapper objectMapper;

    public record ComputedDeduction(SalaryComponent component, BigDecimal amount) {
    }

    public List<ComputedDeduction> computeDeductions(Employee employee, BigDecimal basicAmount, BigDecimal grossPay, LocalDate cycleStart, LocalDate cycleEnd) {
        List<ComputedDeduction> deductions = new ArrayList<>();
        if (employee.getLocation() == null || employee.getLocation().getCountry() == null) {
            return deductions;
        }
        String country = employee.getLocation().getCountry();
        UUID employeeId = employee.getUserId();

        computePf(employeeId, country, cycleStart, cycleEnd, basicAmount).ifPresent(deductions::add);
        computeEsi(employeeId, country, cycleStart, cycleEnd, grossPay).ifPresent(deductions::add);
        computeProfessionalTax(employeeId, country, cycleStart, cycleEnd).ifPresent(deductions::add);
        return deductions;
    }

    private Optional<ComputedDeduction> computePf(UUID employeeId, String country, LocalDate cycleStart, LocalDate cycleEnd, BigDecimal basicAmount) {
        Optional<BigDecimal> overridden = override(employeeId, PF, cycleStart, cycleEnd);
        if (overridden.isPresent()) {
            return component(PF).map(catalog -> new ComputedDeduction(catalog, overridden.get()));
        }
        return rule(country, PF, cycleEnd).flatMap(rule -> component(PF).map(catalog -> {
            JsonNode slab = parse(rule);
            BigDecimal rate = decimal(slab, "employeeRate");
            BigDecimal ceiling = decimal(slab, "wageCeiling");
            BigDecimal base = basicAmount.min(ceiling);
            return new ComputedDeduction(catalog, round(base.multiply(rate)));
        })).filter(d -> d.amount().signum() > 0);
    }

    private Optional<ComputedDeduction> computeEsi(UUID employeeId, String country, LocalDate cycleStart, LocalDate cycleEnd, BigDecimal grossPay) {
        Optional<BigDecimal> overridden = override(employeeId, ESI, cycleStart, cycleEnd);
        if (overridden.isPresent()) {
            return component(ESI).map(catalog -> new ComputedDeduction(catalog, overridden.get()));
        }
        return rule(country, ESI, cycleEnd).flatMap(rule -> component(ESI).flatMap(catalog -> {
            JsonNode slab = parse(rule);
            BigDecimal rate = decimal(slab, "employeeRate");
            BigDecimal ceiling = decimal(slab, "wageCeiling");
            if (grossPay.compareTo(ceiling) > 0) {
                return Optional.empty(); // not eligible this cycle — ESI has no partial/capped amount, it's all-or-nothing
            }
            return Optional.of(new ComputedDeduction(catalog, round(grossPay.multiply(rate))));
        })).filter(d -> d.amount().signum() > 0);
    }

    private Optional<ComputedDeduction> computeProfessionalTax(UUID employeeId, String country, LocalDate cycleStart, LocalDate cycleEnd) {
        Optional<BigDecimal> overridden = override(employeeId, PROFESSIONAL_TAX, cycleStart, cycleEnd);
        if (overridden.isPresent()) {
            return component("Professional Tax").map(catalog -> new ComputedDeduction(catalog, overridden.get()));
        }
        return rule(country, PROFESSIONAL_TAX, cycleEnd).flatMap(rule -> component("Professional Tax").map(catalog -> {
            JsonNode slab = parse(rule);
            return new ComputedDeduction(catalog, round(decimal(slab, "amount")));
        })).filter(d -> d.amount().signum() > 0);
    }

    // Keka's "Statutory Override" step — checked FIRST, same precedence rule as
    // PayrollLopCalculation#effectiveLopDays preferring an override over the computed value. Unlike
    // that one, there's no "computed" value stored alongside it here to fall back on if this is
    // empty — an absent override just means "use the StatutoryRule formula instead," handled by
    // each compute* method's own caller.
    private Optional<BigDecimal> override(UUID employeeId, String ruleType, LocalDate cycleStart, LocalDate cycleEnd) {
        return statutoryDeductionOverrideRepository
                .findByEmployeeIdAndRuleTypeAndCycleStartAndCycleEnd(employeeId, ruleType, cycleStart, cycleEnd)
                .map(StatutoryDeductionOverride::getOverrideAmount);
    }

    private Optional<StatutoryRule> rule(String country, String ruleType, LocalDate asOf) {
        return statutoryRuleRepository.findEffectiveAt(country, ruleType, asOf);
    }

    private Optional<SalaryComponent> component(String reservedName) {
        return salaryComponentRepository.findByNameIgnoreCase(reservedName).filter(SalaryComponent::isActive);
    }

    private JsonNode parse(StatutoryRule rule) {
        try {
            return objectMapper.readTree(rule.getSlabConfig());
        } catch (Exception e) {
            throw new IllegalStateException("Malformed slabConfig for " + rule.getCountry() + "/" + rule.getRuleType() + ": " + rule.getId(), e);
        }
    }

    private BigDecimal decimal(JsonNode slab, String field) {
        JsonNode value = slab.get(field);
        if (value == null) {
            throw new IllegalStateException("slabConfig is missing required field '" + field + "'");
        }
        return new BigDecimal(value.asText());
    }

    private BigDecimal round(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}

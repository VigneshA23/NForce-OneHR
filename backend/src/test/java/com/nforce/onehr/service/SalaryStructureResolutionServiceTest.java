package com.nforce.onehr.service;

import com.nforce.onehr.entity.SalaryCalculationType;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.SalaryStructureComponent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalaryStructureResolutionServiceTest {

    private final SalaryStructureResolutionService service = new SalaryStructureResolutionService();

    private SalaryComponent catalog(String name, String type) {
        return SalaryComponent.builder().id(UUID.randomUUID()).name(name).type(type).build();
    }

    private SalaryStructureComponent sc(SalaryComponent catalog, String calcType, BigDecimal value, UUID referenceId) {
        return SalaryStructureComponent.builder().id(UUID.randomUUID()).component(catalog)
                .calcType(calcType).value(value).referenceStructureComponentId(referenceId).build();
    }

    private void assertCloseTo(BigDecimal expected, BigDecimal actual, String message) {
        BigDecimal diff = expected.subtract(actual).abs();
        org.junit.jupiter.api.Assertions.assertTrue(diff.compareTo(new BigDecimal("5.00")) < 0,
                message + " — expected ~" + expected + " but got " + actual);
    }

    // No CONTRIBUTION component present — effectiveGross must equal ctcAnnual on the very first
    // pass (contributionsTotal starts at zero and never moves), so this is byte-for-byte the same
    // result the pre-iteration version of this method always produced. Guards against the
    // CTC-vs-Gross generalization silently changing behavior for every structure that has no
    // Employer PF/Gratuity configured yet.
    @Test
    void resolveAnnualValues_noContributionComponents_behavesExactlyAsPercentOfCtc() {
        SalaryStructureComponent basic = sc(catalog("Basic", SalaryComponentType.EARNING),
                SalaryCalculationType.PERCENT_OF_GROSS, new BigDecimal("50"), null);
        SalaryStructureComponent hra = sc(catalog("HRA", SalaryComponentType.EARNING),
                SalaryCalculationType.PERCENT_OF_COMPONENT, new BigDecimal("40"), basic.getId());
        SalaryStructureComponent other = sc(catalog("Other Allowance", SalaryComponentType.EARNING),
                SalaryCalculationType.BALANCING_REMAINDER, null, null);

        Map<UUID, BigDecimal> resolved = service.resolveAnnualValues(List.of(basic, hra, other), new BigDecimal("490000"));

        assertEquals(0, new BigDecimal("245000").compareTo(resolved.get(basic.getId())));
        assertEquals(0, new BigDecimal("98000").compareTo(resolved.get(hra.getId())));
        assertEquals(0, new BigDecimal("147000").compareTo(resolved.get(other.getId())));
    }

    // Reproduces a real Keka "Salary Breakdown" screen for CTC 4,90,000/year: Basic = 50% of
    // Gross, HRA = 40% of Basic, Employer PF = 12% of Basic, Gratuity = 15/26/12 of Basic (the
    // standard ~4.81% monthly accrual rate) — both contributions CONTRIBUTION-typed, so they sit
    // inside CTC but outside Gross. Confirmed against the real screen's own numbers: Basic
    // 18,834.00/mo (226,008/yr), Employer PF 2,260.00/mo, Gratuity 906.00/mo, Total Earnings
    // (=CTC) exactly 4,90,000.00/yr.
    @Test
    void resolveAnnualValues_contributionComponents_areExcludedFromGrossButStillSumToCtc() {
        SalaryStructureComponent basic = sc(catalog("Basic", SalaryComponentType.EARNING),
                SalaryCalculationType.PERCENT_OF_GROSS, new BigDecimal("50"), null);
        SalaryStructureComponent hra = sc(catalog("HRA", SalaryComponentType.EARNING),
                SalaryCalculationType.PERCENT_OF_COMPONENT, new BigDecimal("40"), basic.getId());
        SalaryStructureComponent employerPf = sc(catalog("Employer PF", SalaryComponentType.CONTRIBUTION),
                SalaryCalculationType.PERCENT_OF_COMPONENT, new BigDecimal("12"), basic.getId());
        SalaryStructureComponent gratuity = sc(catalog("Gratuity", SalaryComponentType.CONTRIBUTION),
                SalaryCalculationType.PERCENT_OF_COMPONENT, new BigDecimal("4.807692"), basic.getId());
        SalaryStructureComponent other = sc(catalog("Other Allowance", SalaryComponentType.EARNING),
                SalaryCalculationType.BALANCING_REMAINDER, null, null);

        BigDecimal ctcAnnual = new BigDecimal("490000");
        Map<UUID, BigDecimal> resolved = service.resolveAnnualValues(
                List.of(basic, hra, employerPf, gratuity, other), ctcAnnual);

        assertCloseTo(new BigDecimal("226008"), resolved.get(basic.getId()), "Basic annual");
        assertCloseTo(new BigDecimal("27120"), resolved.get(employerPf.getId()), "Employer PF annual");
        assertCloseTo(new BigDecimal("10865"), resolved.get(gratuity.getId()), "Gratuity annual");

        // The defining property, independent of rounding: Gross (earnings) + contributions must
        // reconstruct the original CTC exactly — contributions are additive ON TOP of Gross, never
        // inside it.
        BigDecimal earningsTotal = resolved.get(basic.getId()).add(resolved.get(hra.getId())).add(resolved.get(other.getId()));
        BigDecimal contributionsTotal = resolved.get(employerPf.getId()).add(resolved.get(gratuity.getId()));
        assertCloseTo(ctcAnnual, earningsTotal.add(contributionsTotal), "Gross + contributions must reconstruct CTC");
    }
}

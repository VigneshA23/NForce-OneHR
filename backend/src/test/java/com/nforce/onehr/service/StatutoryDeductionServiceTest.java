package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.StatutoryDeductionOverride;
import com.nforce.onehr.entity.StatutoryRule;
import com.nforce.onehr.repository.SalaryComponentRepository;
import com.nforce.onehr.repository.StatutoryDeductionOverrideRepository;
import com.nforce.onehr.repository.StatutoryRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** US-C13 Phase 3/4 — pure Mockito unit tests, same convention as every other *ServiceTest here. */
@ExtendWith(MockitoExtension.class)
class StatutoryDeductionServiceTest {

    @Mock private StatutoryRuleRepository statutoryRuleRepository;
    @Mock private StatutoryDeductionOverrideRepository statutoryDeductionOverrideRepository;
    @Mock private SalaryComponentRepository salaryComponentRepository;

    private StatutoryDeductionService service;
    private final LocalDate cycleStart = LocalDate.of(2026, 10, 26);
    private final LocalDate cycleEnd = LocalDate.of(2026, 11, 25);

    @BeforeEach
    void setUp() {
        service = new StatutoryDeductionService(statutoryRuleRepository, statutoryDeductionOverrideRepository, salaryComponentRepository, new ObjectMapper());
        // No override for anything by default — tests that care about the override path stub this
        // explicitly; everyone else exercises the plain StatutoryRule formula path.
        lenient().when(statutoryDeductionOverrideRepository.findByEmployeeIdAndRuleTypeAndCycleStartAndCycleEnd(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private Employee employeeIn(String country) {
        Location location = country == null ? null : Location.builder().id(UUID.randomUUID()).country(country).build();
        return Employee.builder().userId(UUID.randomUUID()).location(location).build();
    }

    private StatutoryRule rule(String ruleType, String slabConfig) {
        return StatutoryRule.builder().id(UUID.randomUUID()).country("India").ruleType(ruleType)
                .slabConfig(slabConfig).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
    }

    private SalaryComponent catalog(String name) {
        return SalaryComponent.builder().id(UUID.randomUUID()).name(name).type(SalaryComponentType.DEDUCTION).active(true).build();
    }

    private void stubPfAndEsiAbsent() {
        lenient().when(statutoryRuleRepository.findEffectiveAt(eq("India"), eq("ESI"), any())).thenReturn(Optional.empty());
        lenient().when(statutoryRuleRepository.findEffectiveAt(eq("India"), eq("PROFESSIONAL_TAX"), any())).thenReturn(Optional.empty());
    }

    @Test
    void pf_underWageCeiling_isTwelvePercentOfBasic() {
        when(statutoryRuleRepository.findEffectiveAt("India", "PF", cycleEnd))
                .thenReturn(Optional.of(rule("PF", "{\"employeeRate\":0.12,\"wageCeiling\":25000}")));
        when(salaryComponentRepository.findByNameIgnoreCase("PF")).thenReturn(Optional.of(catalog("PF")));
        stubPfAndEsiAbsent();

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employeeIn("India"), new BigDecimal("19058"), new BigDecimal("40000"), cycleStart, cycleEnd);

        assertEquals(1, result.size());
        // 19,058 x 0.12 = 2,286.96 exactly (no third decimal to round away) — this class's own
        // Javadoc casually writes the same real payslip figure as "-> 2,287" (whole-rupee display),
        // but every stored amount in this codebase is HALF_UP to 2 decimals (Decision #10), so the
        // precise, correctly-rounded value here is 2,286.96, not 2,287.00.
        assertEquals(0, new BigDecimal("2286.96").compareTo(result.get(0).amount()));
    }

    @Test
    void pf_overWageCeiling_isCappedAtCeilingTimesRate() {
        when(statutoryRuleRepository.findEffectiveAt("India", "PF", cycleEnd))
                .thenReturn(Optional.of(rule("PF", "{\"employeeRate\":0.12,\"wageCeiling\":15000}")));
        when(salaryComponentRepository.findByNameIgnoreCase("PF")).thenReturn(Optional.of(catalog("PF")));
        stubPfAndEsiAbsent();

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employeeIn("India"), new BigDecimal("30000"), new BigDecimal("50000"), cycleStart, cycleEnd);

        assertEquals(1, result.size());
        assertEquals(0, new BigDecimal("1800.00").compareTo(result.get(0).amount()));
    }

    @Test
    void esi_withinEligibilityCeiling_isDeducted() {
        lenient().when(statutoryRuleRepository.findEffectiveAt(eq("India"), eq("PF"), any())).thenReturn(Optional.empty());
        lenient().when(statutoryRuleRepository.findEffectiveAt(eq("India"), eq("PROFESSIONAL_TAX"), any())).thenReturn(Optional.empty());
        when(statutoryRuleRepository.findEffectiveAt("India", "ESI", cycleEnd))
                .thenReturn(Optional.of(rule("ESI", "{\"employeeRate\":0.0075,\"wageCeiling\":21000}")));
        when(salaryComponentRepository.findByNameIgnoreCase("ESI")).thenReturn(Optional.of(catalog("ESI")));

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employeeIn("India"), new BigDecimal("12000"), new BigDecimal("18000"), cycleStart, cycleEnd);

        assertEquals(1, result.size());
        assertEquals(0, new BigDecimal("135.00").compareTo(result.get(0).amount()));
    }

    @Test
    void esi_aboveEligibilityCeiling_isNotDeductedAtAll() {
        lenient().when(statutoryRuleRepository.findEffectiveAt(eq("India"), eq("PF"), any())).thenReturn(Optional.empty());
        lenient().when(statutoryRuleRepository.findEffectiveAt(eq("India"), eq("PROFESSIONAL_TAX"), any())).thenReturn(Optional.empty());
        when(statutoryRuleRepository.findEffectiveAt("India", "ESI", cycleEnd))
                .thenReturn(Optional.of(rule("ESI", "{\"employeeRate\":0.0075,\"wageCeiling\":21000}")));

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employeeIn("India"), new BigDecimal("12000"), new BigDecimal("25000"), cycleStart, cycleEnd);

        assertTrue(result.isEmpty());
    }

    @Test
    void professionalTax_withRuleConfigured_isFlatAmount() {
        lenient().when(statutoryRuleRepository.findEffectiveAt(eq("India"), eq("PF"), any())).thenReturn(Optional.empty());
        lenient().when(statutoryRuleRepository.findEffectiveAt(eq("India"), eq("ESI"), any())).thenReturn(Optional.empty());
        when(statutoryRuleRepository.findEffectiveAt("India", "PROFESSIONAL_TAX", cycleEnd))
                .thenReturn(Optional.of(rule("PROFESSIONAL_TAX", "{\"amount\":200}")));
        when(salaryComponentRepository.findByNameIgnoreCase("Professional Tax")).thenReturn(Optional.of(catalog("Professional Tax")));

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employeeIn("India"), new BigDecimal("12000"), new BigDecimal("30000"), cycleStart, cycleEnd);

        assertEquals(1, result.size());
        assertEquals(0, new BigDecimal("200.00").compareTo(result.get(0).amount()));
    }

    @Test
    void noRuleConfiguredAtAll_computesNothingAndDoesNotThrow() {
        lenient().when(statutoryRuleRepository.findEffectiveAt(any(), any(), any())).thenReturn(Optional.empty());

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employeeIn("India"), new BigDecimal("20000"), new BigDecimal("30000"), cycleStart, cycleEnd);

        assertTrue(result.isEmpty());
    }

    @Test
    void ruleConfiguredButCatalogComponentMissing_isSkipped() {
        when(statutoryRuleRepository.findEffectiveAt("India", "PF", cycleEnd))
                .thenReturn(Optional.of(rule("PF", "{\"employeeRate\":0.12,\"wageCeiling\":25000}")));
        when(salaryComponentRepository.findByNameIgnoreCase("PF")).thenReturn(Optional.empty());
        stubPfAndEsiAbsent();

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employeeIn("India"), new BigDecimal("20000"), new BigDecimal("30000"), cycleStart, cycleEnd);

        assertTrue(result.isEmpty());
    }

    @Test
    void ruleConfiguredButCatalogComponentInactive_isSkipped() {
        when(statutoryRuleRepository.findEffectiveAt("India", "PF", cycleEnd))
                .thenReturn(Optional.of(rule("PF", "{\"employeeRate\":0.12,\"wageCeiling\":25000}")));
        SalaryComponent inactive = catalog("PF");
        inactive.setActive(false);
        when(salaryComponentRepository.findByNameIgnoreCase("PF")).thenReturn(Optional.of(inactive));
        stubPfAndEsiAbsent();

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employeeIn("India"), new BigDecimal("20000"), new BigDecimal("30000"), cycleStart, cycleEnd);

        assertTrue(result.isEmpty());
    }

    @Test
    void employeeWithNoLocation_computesNothingAndDoesNotThrow() {
        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employeeIn(null), new BigDecimal("20000"), new BigDecimal("30000"), cycleStart, cycleEnd);

        assertTrue(result.isEmpty());
    }

    @Test
    void override_takesPrecedenceOverTheFormula_evenWhenARuleIsConfigured() {
        Employee employee = employeeIn("India");
        // Never actually consulted — the override short-circuits before this would be read. Stubbed
        // anyway (leniently) to prove the point: even a rule being configured doesn't matter.
        lenient().when(statutoryRuleRepository.findEffectiveAt("India", "PF", cycleEnd))
                .thenReturn(Optional.of(rule("PF", "{\"employeeRate\":0.12,\"wageCeiling\":25000}")));
        when(salaryComponentRepository.findByNameIgnoreCase("PF")).thenReturn(Optional.of(catalog("PF")));
        stubPfAndEsiAbsent();
        when(statutoryDeductionOverrideRepository.findByEmployeeIdAndRuleTypeAndCycleStartAndCycleEnd(employee.getUserId(), "PF", cycleStart, cycleEnd))
                .thenReturn(Optional.of(StatutoryDeductionOverride.builder().overrideAmount(new BigDecimal("500.00")).build()));

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employee, new BigDecimal("19058"), new BigDecimal("40000"), cycleStart, cycleEnd);

        assertEquals(1, result.size());
        assertEquals(0, new BigDecimal("500.00").compareTo(result.get(0).amount()));
    }

    @Test
    void override_withNoCatalogComponent_isStillSkipped() {
        Employee employee = employeeIn("India");
        stubPfAndEsiAbsent();
        lenient().when(statutoryRuleRepository.findEffectiveAt(eq("India"), eq("PF"), any())).thenReturn(Optional.empty());
        when(salaryComponentRepository.findByNameIgnoreCase("PF")).thenReturn(Optional.empty());
        when(statutoryDeductionOverrideRepository.findByEmployeeIdAndRuleTypeAndCycleStartAndCycleEnd(employee.getUserId(), "PF", cycleStart, cycleEnd))
                .thenReturn(Optional.of(StatutoryDeductionOverride.builder().overrideAmount(new BigDecimal("500.00")).build()));

        List<StatutoryDeductionService.ComputedDeduction> result =
                service.computeDeductions(employee, new BigDecimal("19058"), new BigDecimal("40000"), cycleStart, cycleEnd);

        assertTrue(result.isEmpty());
    }
}

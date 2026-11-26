package com.nforce.onehr.service;

import com.nforce.onehr.entity.OtRateRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtConfigServiceTest {

    private final OtConfigService service = new OtConfigService(null, null, null, null, null, null);
    private final UUID dept = UUID.randomUUID();
    private final UUID shift = UUID.randomUUID();

    private OtRateRule rule(String day, UUID d, UUID s, String mult, LocalDate from, LocalDate to) {
        return OtRateRule.builder().id(UUID.randomUUID()).name(day).dayType(day).departmentId(d).shiftId(s)
                .multiplier(new BigDecimal(mult)).effectiveFrom(from).effectiveTo(to).active(true).build();
    }

    @Test
    void picksMostSpecificRule() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        OtRateRule generic = rule("WEEKDAY", null, null, "1.5", from, null);
        OtRateRule deptSpecific = rule("WEEKDAY", dept, null, "1.75", from, null);
        var chosen = service.resolveRule(List.of(generic, deptSpecific), "WEEKDAY", dept, shift, LocalDate.of(2026, 5, 5));
        assertEquals(new BigDecimal("1.75"), chosen.orElseThrow().getMultiplier());
    }

    @Test
    void respectsEffectiveDates() {
        OtRateRule old = rule("ANY", null, null, "1.5", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31));
        OtRateRule updated = rule("ANY", null, null, "2.0", LocalDate.of(2026, 6, 1), null);
        assertEquals(new BigDecimal("1.5"),
                service.resolveRule(List.of(old, updated), "WEEKDAY", dept, shift, LocalDate.of(2026, 5, 31)).orElseThrow().getMultiplier());
        assertEquals(new BigDecimal("2.0"),
                service.resolveRule(List.of(old, updated), "WEEKDAY", dept, shift, LocalDate.of(2026, 6, 1)).orElseThrow().getMultiplier());
    }

    @Test
    void noRuleWhenDayTypeDoesNotMatch() {
        OtRateRule holiday = rule("HOLIDAY", null, null, "2.0", LocalDate.of(2026, 1, 1), null);
        assertTrue(service.resolveRule(List.of(holiday), "WEEKDAY", dept, shift, LocalDate.of(2026, 5, 5)).isEmpty());
    }

    @Test
    void amountFormula() {
        // 2h at 100/h with 1.5x = 300
        assertEquals(new BigDecimal("300.00"),
                OtCalculationService.amountFor(120, new BigDecimal("100"), new BigDecimal("1.5")));
    }
}

package com.nforce.onehr.service;

import com.nforce.onehr.service.IncomeTaxService.Rules;
import com.nforce.onehr.service.IncomeTaxService.Slab;
import com.nforce.onehr.service.IncomeTaxService.Tax;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Edge values around the New Regime slabs (FY 2025-26 seed): 4L, 12L (87A threshold), 24L, 50L (surcharge). */
class IncomeTaxServiceTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static final Rules NEW = new Rules(
            List.of(new Slab(d("0"), d("400000"), d("0")), new Slab(d("400000"), d("800000"), d("0.05")),
                    new Slab(d("800000"), d("1200000"), d("0.10")), new Slab(d("1200000"), d("1600000"), d("0.15")),
                    new Slab(d("1600000"), d("2000000"), d("0.20")), new Slab(d("2000000"), d("2400000"), d("0.25")),
                    new Slab(d("2400000"), null, d("0.30"))),
            d("75000"), d("1200000"), d("60000"), d("0.04"),
            List.of(new Slab(d("5000000"), null, d("0.10")), new Slab(d("10000000"), null, d("0.15"))));

    private static Tax tax(String income) { return IncomeTaxService.compute(NEW, d(income)); }

    @Test void nothingDueUpToFourLakh() { assertEquals(0, tax("400000").netPayable().intValue()); }

    @Test void oneRupeeOverFourLakhIsRebatedAway() { assertEquals(0, tax("400001").netPayable().intValue()); }

    @Test void exactlyTwelveLakhGetsFullRebate() {
        Tax t = tax("1200000");
        assertEquals(0, d("60000").compareTo(t.slabTotal()));
        assertEquals(0, d("60000").compareTo(t.rebate()));
        assertEquals(0, t.netPayable().intValue());
    }

    @Test void oneRupeeOverTwelveLakhLosesRebate() {
        // slab tax 60,000.15, plus 4% cess = 62,400.156 -> 62,400
        assertEquals(62400, tax("1200001").netPayable().intValue());
    }

    @Test void twentyFourLakhPlusCess() {
        // 0 + 20000 + 40000 + 60000 + 80000 + 100000 = 300,000; cess 12,000
        assertEquals(312000, tax("2400000").netPayable().intValue());
    }

    @Test void surchargeOnlyAboveFiftyLakh() {
        assertEquals(0, BigDecimal.ZERO.compareTo(tax("5000000").surcharge()));
        assertEquals(true, tax("5000001").surcharge().signum() > 0);
    }

    @Test void cessIsFourPercentOfTaxPlusSurcharge() {
        Tax t = tax("6000000");
        assertEquals(0, t.grossTax().add(t.surcharge()).multiply(d("0.04")).compareTo(t.cess()));
    }
}

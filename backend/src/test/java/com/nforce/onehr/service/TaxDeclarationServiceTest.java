package com.nforce.onehr.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What an item contributes to tax, and the rule that a reviewer can't decide their own proofs. */
class TaxDeclarationServiceTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test void undecidedCountsDeclaredWhileProofWindowIsOpen() {
        assertEquals(d("50000"), TaxDeclarationService.counted(d("50000"), "NONE", d("0"), false));
        assertEquals(d("50000"), TaxDeclarationService.counted(d("50000"), "PENDING", d("0"), false));
    }

    @Test void undecidedCountsZeroOnceProofWindowClosed() {
        assertEquals(d("0"), TaxDeclarationService.counted(d("50000"), "NONE", d("0"), true));
        assertEquals(d("0"), TaxDeclarationService.counted(d("50000"), "PENDING", d("0"), true));
    }

    @Test void decisionOverridesWindow() {
        assertEquals(d("30000"), TaxDeclarationService.counted(d("50000"), "PARTIAL", d("30000"), true));
        assertEquals(d("50000"), TaxDeclarationService.counted(d("50000"), "ACCEPTED", d("50000"), true));
        assertEquals(d("0"), TaxDeclarationService.counted(d("50000"), "REJECTED", d("0"), false));
    }

    @Test void capsAre80cOneAndHalfLakhAndHouseTwoLakh() {
        assertEquals(d("150000"), TaxDeclarationService.CAP_80C);
        assertEquals(d("200000"), TaxDeclarationService.CAP_HOUSE);
    }

    @Test void unknownDeclarationCannotBeDecided() {
        var svc = new TaxDeclarationService(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class), null, null);
        assertThrows(NoSuchElementException.class, () -> svc.decide(UUID.randomUUID(), UUID.randomUUID(), "ACCEPT", null, null));
    }
}

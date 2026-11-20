package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SensitiveDataMaskerTest {

    @Test
    void keepsOnlyTheLastFourCharacters() {
        assertEquals("XXXX-1234", SensitiveDataMasker.maskId("123456781234"));
        assertEquals("XXXX-234F", SensitiveDataMasker.maskId(" ABCDE1234F "));
    }

    @Test
    void shortValuesAreFullyMasked_soNothingLeaks() {
        assertEquals("XXXX", SensitiveDataMasker.maskId("1234"));
        assertEquals("XXXX", SensitiveDataMasker.maskId("12"));
    }

    @Test
    void nullAndBlankPassThrough_soAnAbsentValueStaysAbsent() {
        assertNull(SensitiveDataMasker.maskId(null));
        assertEquals("  ", SensitiveDataMasker.maskId("  "));
    }
}

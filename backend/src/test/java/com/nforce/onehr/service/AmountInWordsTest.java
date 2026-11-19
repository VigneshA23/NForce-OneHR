package com.nforce.onehr.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AmountInWordsTest {

    @Test
    void zero() {
        assertEquals("Zero Rupees Only", AmountInWords.inWords(BigDecimal.ZERO));
    }

    @Test
    void theReferencePayslipsExactFigure() {
        assertEquals("Thirty Seven Thousand Two Hundred and Sixteen Rupees Only",
                AmountInWords.inWords(new BigDecimal("37216.00")));
    }

    @Test
    void includesPaiseWhenPresent() {
        assertEquals("One Hundred Rupees and Fifty Paise Only", AmountInWords.inWords(new BigDecimal("100.50")));
    }

    @Test
    void lakhsAndCrores() {
        assertEquals("One Lakh Rupees Only", AmountInWords.inWords(new BigDecimal("100000")));
        assertEquals("One Crore Rupees Only", AmountInWords.inWords(new BigDecimal("10000000")));
        assertEquals("Twelve Lakh Thirty Four Thousand Five Hundred and Sixty Seven Rupees Only",
                AmountInWords.inWords(new BigDecimal("1234567")));
    }

    @Test
    void tensWithoutATrailingOne() {
        assertEquals("Ninety Rupees Only", AmountInWords.inWords(new BigDecimal("90")));
        assertEquals("Twenty One Rupees Only", AmountInWords.inWords(new BigDecimal("21")));
    }

    @Test
    void negativeAmountsUseTheAbsoluteValue() {
        assertEquals("Five Hundred Rupees Only", AmountInWords.inWords(new BigDecimal("-500")));
    }

    @Test
    void nullIsZero() {
        assertEquals("Zero Rupees Only", AmountInWords.inWords(null));
    }
}

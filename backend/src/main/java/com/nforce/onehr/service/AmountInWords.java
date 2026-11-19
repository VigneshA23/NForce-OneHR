package com.nforce.onehr.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Spells out a rupee amount in words, Indian numbering (Crore/Lakh/Thousand), for the payslip's
 * "Net Salary in Words" line — Keka's own payslip always shows this, unconditionally, so this has
 * no settings toggle of its own.
 */
public final class AmountInWords {

    private static final String[] ONES = {
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen",
    };
    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety",
    };

    public static String inWords(BigDecimal amount) {
        BigDecimal abs = (amount == null ? BigDecimal.ZERO : amount).abs();
        long rupees = abs.setScale(0, RoundingMode.DOWN).longValue();
        int paise = abs.subtract(BigDecimal.valueOf(rupees)).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue();

        StringBuilder out = new StringBuilder(rupees == 0 ? "Zero" : convert(rupees)).append(" Rupees");
        if (paise > 0) {
            out.append(" and ").append(convert(paise)).append(" Paise");
        }
        return out.append(" Only").toString();
    }

    private static String convert(long n) {
        if (n == 0) return "";
        StringBuilder sb = new StringBuilder();
        long crore = n / 10_000_000; n %= 10_000_000;
        long lakh = n / 100_000; n %= 100_000;
        long thousand = n / 1_000; n %= 1_000;
        long hundred = n / 100; n %= 100;

        if (crore > 0) sb.append(twoDigits(crore)).append(" Crore ");
        if (lakh > 0) sb.append(twoDigits(lakh)).append(" Lakh ");
        if (thousand > 0) sb.append(twoDigits(thousand)).append(" Thousand ");
        if (hundred > 0) sb.append(ONES[(int) hundred]).append(" Hundred ");
        if (n > 0) {
            if (!sb.isEmpty()) sb.append("and ");
            sb.append(twoDigits(n));
        }
        return sb.toString().trim();
    }

    private static String twoDigits(long n) {
        if (n < 20) return ONES[(int) n];
        String tens = TENS[(int) (n / 10)];
        int one = (int) (n % 10);
        return one == 0 ? tens : tens + " " + ONES[one];
    }

    private AmountInWords() {
    }
}

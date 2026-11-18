package com.nforce.onehr.security;

/**
 * The one place identifier-like sensitive values (bank account, national ID, passport number, phone)
 * are masked for viewers without {@code employee_sensitive:VIEW:ALL}. Every response, list or export
 * that carries such a value for someone other than the caller must go through here — there is no
 * employee-data export today, but any added later must not bypass it.
 */
public final class SensitiveDataMasker {

    private static final String MASK = "XXXX";

    private SensitiveDataMasker() {}

    /** {@code 123456781234} -> {@code XXXX-1234}. Values of four characters or fewer are fully masked; null/blank pass through. */
    public static String maskId(String value) {
        if (value == null || value.isBlank()) return value;
        String trimmed = value.trim();
        return trimmed.length() <= 4 ? MASK : MASK + "-" + trimmed.substring(trimmed.length() - 4);
    }
}

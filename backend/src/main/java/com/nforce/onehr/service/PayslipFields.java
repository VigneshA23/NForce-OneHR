package com.nforce.onehr.service;

import java.util.List;
import java.util.Map;

/**
 * The payslip's configurable employee-identity fields — Keka restyle's "Payslip Fields" screen
 * (§3.9). Employee Name, Employee Number and Pay Period always print and aren't part of this
 * list; these six are what HR can toggle on/off and reorder.
 */
public final class PayslipFields {

    public static final List<String> VALID_KEYS = List.of(
            "dateOfJoining", "department", "designation", "uan", "esi", "pan");

    /** What a pay group that's never touched this setting renders — exactly the three fields
     * (in exactly this order) PayslipPdfService always printed before this setting existed, so
     * "never configured" never changes an existing payslip's layout. UAN/ESI/PAN only ever
     * appear once HR explicitly adds them. */
    public static final List<String> DEFAULT_KEYS = List.of("department", "designation", "dateOfJoining");

    public static final Map<String, String> LABEL = Map.of(
            "dateOfJoining", "Date of Joining",
            "department", "Department",
            "designation", "Designation",
            "uan", "UAN",
            "esi", "ESI Number",
            "pan", "PAN Number");

    /** Decodes {@link com.nforce.onehr.entity.PayslipSettings#getPayslipFields()}'s stored,
     * comma-separated form. Blank/null means "never configured" → {@link #DEFAULT_KEYS}. */
    public static List<String> decode(String stored) {
        if (stored == null || stored.isBlank()) return DEFAULT_KEYS;
        return List.of(stored.split(","));
    }

    private PayslipFields() {
    }
}

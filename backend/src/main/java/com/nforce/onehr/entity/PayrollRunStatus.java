package com.nforce.onehr.entity;

/** US-C08 — DRAFT → SUBMITTED → APPROVED → DISBURSED, matching the dev notes' own vocabulary (Decision #2). */
public final class PayrollRunStatus {
    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String DISBURSED = "DISBURSED";

    private PayrollRunStatus() {}
}

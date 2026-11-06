package com.nforce.onehr.entity;

/** US-C07 — why a {@link PayrollProration} row has a factor below 1 (or not). Plain constants, same convention as the other payroll status-like fields this epic. */
public final class ProrationType {
    public static final String FULL_CYCLE = "FULL_CYCLE";
    public static final String JOINER = "JOINER";
    public static final String EXIT = "EXIT";
    public static final String JOINER_AND_EXIT = "JOINER_AND_EXIT";

    private ProrationType() {}
}

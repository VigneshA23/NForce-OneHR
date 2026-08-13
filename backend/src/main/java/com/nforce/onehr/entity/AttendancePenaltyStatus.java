package com.nforce.onehr.entity;

/** Allowed values for {@link AttendancePenalty#getStatus()} — a closed set, same convention as ExceptionType. */
public final class AttendancePenaltyStatus {

    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String APPLIED = "APPLIED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REVERSED = "REVERSED";

    private AttendancePenaltyStatus() {}
}

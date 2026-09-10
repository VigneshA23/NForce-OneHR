package com.nforce.onehr.entity;

/**
 * Allowed values for {@link AttendancePenalty#getStatus()} — a closed set, same convention as
 * ExceptionType. Gap-035: there is no review/approval step in this product today — a penalty is
 * created directly usable (deducted/notified) and stays {@code PENDING_REVIEW} until cancelled or
 * reversed, so a separate {@code APPLIED} state was removed rather than left unreachable dead code.
 * If a genuine review step is ever added, introduce the new state deliberately then, rather than
 * resurrecting this name for a different purpose than its removal implied.
 */
public final class AttendancePenaltyStatus {

    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String CANCELLED = "CANCELLED";
    public static final String REVERSED = "REVERSED";

    private AttendancePenaltyStatus() {}
}

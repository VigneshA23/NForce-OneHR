package com.nforce.onehr.entity;

/**
 * Valid values for {@link LeaveRequest#getHalfDaySession()} — mirrors the
 * {@code chk_leave_requests_half_day_session} check constraint (see V204__add_half_day_session.sql).
 * Plain string constants (not an enum) so they can be used directly as {@code switch} case labels
 * and JPA column values without a converter — same convention as {@link LeaveDurationType}.
 */
public final class LeaveHalfDaySession {

    public static final String FIRST_HALF = "FIRST_HALF";
    public static final String SECOND_HALF = "SECOND_HALF";

    private LeaveHalfDaySession() {
    }
}

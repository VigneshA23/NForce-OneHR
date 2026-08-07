package com.nforce.onehr.service;

import java.util.Map;
import java.util.Set;

/**
 * Help Desk ticket lifecycle. Persisted as the plain {@code VARCHAR} {@code helpdesk_tickets.status}
 * column (this codebase's established convention for status fields — see LeaveRequest,
 * OnboardingChecklist) — this enum exists purely for compile-time safety and to centralize the
 * allowed-transition rules in one place instead of scattering string comparisons through the service.
 */
public enum TicketStatus {
    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    WAITING_FOR_EMPLOYEE,
    RESOLVED,
    CLOSED;

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED_TRANSITIONS = Map.of(
            OPEN, Set.of(ASSIGNED, IN_PROGRESS, CLOSED),
            ASSIGNED, Set.of(IN_PROGRESS, OPEN, CLOSED),
            IN_PROGRESS, Set.of(WAITING_FOR_EMPLOYEE, RESOLVED, ASSIGNED, CLOSED),
            WAITING_FOR_EMPLOYEE, Set.of(IN_PROGRESS, RESOLVED, CLOSED),
            RESOLVED, Set.of(CLOSED, IN_PROGRESS),
            CLOSED, Set.of()
    );

    public boolean canTransitionTo(TicketStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public static TicketStatus from(String raw) {
        try {
            return TicketStatus.valueOf(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown ticket status: " + raw);
        }
    }
}

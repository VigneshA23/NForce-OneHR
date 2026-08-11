package com.nforce.onehr.service;

import java.util.Map;
import java.util.Set;

/**
 * Help Desk ticket lifecycle. Persisted as the plain {@code VARCHAR} {@code helpdesk_tickets.status}
 * column (this codebase's established convention for status fields — see LeaveRequest,
 * OnboardingChecklist) — this enum exists purely for compile-time safety and to centralize the
 * allowed-transition rules in one place instead of scattering string comparisons through the service.
 *
 * <p>Strictly linear four-state lifecycle (simplified from a prior six-state model that allowed
 * reopening and ad-hoc shortcuts — see migration V93): {@code OPEN -> IN_PROGRESS -> RESOLVED ->
 * CLOSED}. There is deliberately no path back to an earlier state from anywhere, including from
 * {@code RESOLVED}, and no shortcut directly into {@code CLOSED} except from {@code RESOLVED}.
 * {@code CLOSED} is permanently terminal.
 */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED;

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED_TRANSITIONS = Map.of(
            OPEN, Set.of(IN_PROGRESS),
            IN_PROGRESS, Set.of(RESOLVED),
            RESOLVED, Set.of(CLOSED),
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

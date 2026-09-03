package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpdeskTicket;
import com.nforce.onehr.entity.User;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.UUID;

/**
 * Specification factory for {@link HelpdeskTicketRepository}, mirroring AuditLogSpecifications:
 * a handful of independently-optional filters that combine in any subset. Each factory returns
 * an always-true predicate when its filter isn't supplied, so callers can chain every filter
 * unconditionally with {@code Specification.allOf(...)} regardless of which are actually set.
 */
public final class HelpdeskTicketSpecifications {

    private HelpdeskTicketSpecifications() {
    }

    public static Specification<HelpdeskTicket> employeeIs(UUID employeeUserId) {
        return (root, query, cb) -> cb.equal(root.get("employeeUserId"), employeeUserId);
    }

    // Excludes tickets filed by a since-soft-deleted requester, for the HR-wide queue (listQueue)
    // — employeeUserId is a plain UUID column here (no @ManyToOne to User, same as elsewhere in
    // this codebase), so this uses a subquery instead of a join.
    public static Specification<HelpdeskTicket> requesterNotDeleted() {
        return (root, query, cb) -> {
            Subquery<UUID> activeUserIds = query.subquery(UUID.class);
            var u = activeUserIds.from(User.class);
            activeUserIds.select(u.get("id")).where(cb.isNull(u.get("deletedAt")));
            return root.get("employeeUserId").in(activeUserIds);
        };
    }

    public static Specification<HelpdeskTicket> statusIs(String status) {
        if (status == null || status.isBlank()) return (root, query, cb) -> cb.conjunction();
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<HelpdeskTicket> statusIn(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return (root, query, cb) -> cb.conjunction();
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    public static Specification<HelpdeskTicket> assignedTo(UUID assigneeUserId) {
        if (assigneeUserId == null) return (root, query, cb) -> cb.conjunction();
        return (root, query, cb) -> cb.equal(root.get("assignedTo"), assigneeUserId);
    }

    /** Matches on ticket number (case-insensitive, partial) or description text. */
    public static Specification<HelpdeskTicket> searchText(String search) {
        if (search == null || search.isBlank()) return (root, query, cb) -> cb.conjunction();
        String like = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("ticketNumber")), like),
                cb.like(cb.lower(root.get("description")), like)
        );
    }
}

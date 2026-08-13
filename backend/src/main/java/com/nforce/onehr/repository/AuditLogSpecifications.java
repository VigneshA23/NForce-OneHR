package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AuditLog;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Small Specification factory for {@link AuditLogRepository}. Specifications (new to this
 * codebase) were chosen over a pile of derived-query methods or one giant {@code @Query} because
 * the audit read API has ~5 independent optional filters that combine in any subset, and
 * {@code audit_log} is expected to be the largest, fastest-growing table — unlike
 * RegularizationService's in-memory {@code findAll().stream().filter(...)}, filtering here must
 * happen at the DB level.
 *
 * <p>The {@code *In} factories all self-guard against a null/empty collection by returning an
 * always-false predicate, rather than letting Hibernate build an invalid empty {@code IN ()}
 * clause — callers never need a separate "no results" branch.
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLog> actionIn(Collection<String> actions) {
        if (actions == null || actions.isEmpty()) return (root, query, cb) -> cb.disjunction();
        return (root, query, cb) -> root.get("action").in(actions);
    }

    public static Specification<AuditLog> actionEquals(String action) {
        return (root, query, cb) -> cb.equal(root.get("action"), action);
    }

    public static Specification<AuditLog> actorIdIn(Collection<UUID> actorIds) {
        if (actorIds == null || actorIds.isEmpty()) return (root, query, cb) -> cb.disjunction();
        return (root, query, cb) -> root.get("actorId").in(actorIds);
    }

    public static Specification<AuditLog> targetIdIn(Collection<UUID> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return (root, query, cb) -> cb.disjunction();
        return (root, query, cb) -> root.get("targetId").in(targetIds);
    }

    public static Specification<AuditLog> occurredBetween(Instant from, Instant to) {
        return (root, query, cb) -> {
            if (from != null && to != null) return cb.between(root.get("occurredAt"), from, to);
            if (from != null) return cb.greaterThanOrEqualTo(root.get("occurredAt"), from);
            return cb.lessThanOrEqualTo(root.get("occurredAt"), to);
        };
    }
}

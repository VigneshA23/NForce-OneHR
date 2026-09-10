package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Employee;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.UUID;

/**
 * Small Specification factory for {@link EmployeeRepository}, following the same shape as
 * {@link AuditLogSpecifications} — the Penalization Policy Allocation employee search has ~5
 * independent optional filters (search text, Business Unit, Department, Location, resolved
 * Penalization Policy) that all combine with AND semantics over a table expected to hold
 * thousands of rows, so filtering happens at the DB level with server-side pagination rather than
 * an in-memory {@code findAll().stream().filter(...)}.
 */
public final class EmployeeSpecifications {

    private EmployeeSpecifications() {
    }

    /** Scopes to non-deleted users — every other specification here assumes this is always applied. */
    public static Specification<Employee> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("user").get("deletedAt"));
    }

    public static Specification<Employee> businessUnitIdEquals(UUID businessUnitId) {
        if (businessUnitId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("businessUnit").get("id"), businessUnitId);
    }

    public static Specification<Employee> departmentIdEquals(UUID departmentId) {
        if (departmentId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("department").get("id"), departmentId);
    }

    public static Specification<Employee> locationIdEquals(UUID locationId) {
        if (locationId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("location").get("id"), locationId);
    }

    /** Case-insensitive substring match against full name, employee code, or email. */
    public static Specification<Employee> searchTextMatches(String search) {
        if (search == null || search.isBlank()) return null;
        String pattern = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("fullName")), pattern),
                cb.like(cb.lower(root.get("employeeCode")), pattern),
                cb.like(cb.lower(root.get("user").get("email")), pattern));
    }

    /**
     * Restricts to a specific set of employee ids — used to filter by "resolved Penalization
     * Policy", where the matching id set is computed once by
     * {@link com.nforce.onehr.service.PenalizationPolicyResolutionService#resolveCurrentPolicyIdsByEmployee}
     * (the single authoritative resolution) rather than re-derived here as a second, independent
     * allocation/legacy-FK/default predicate.
     */
    public static Specification<Employee> userIdIn(Collection<UUID> employeeUserIds) {
        if (employeeUserIds == null || employeeUserIds.isEmpty()) return (root, query, cb) -> cb.disjunction();
        return (root, query, cb) -> root.get("userId").in(employeeUserIds);
    }
}

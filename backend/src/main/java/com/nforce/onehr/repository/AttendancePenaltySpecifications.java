package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendancePenalty;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * Specification factory for {@link AttendancePenaltyRepository}, same convention as
 * {@link AuditLogSpecifications}. {@code employeeUserIdIn} is how manager scope (and the
 * department/location/search filters, resolved to a set of matching employee ids by the caller —
 * AttendancePenalty stores only a raw {@code employeeUserId}, not an Employee relation, matching
 * Attendance/AttendanceException/RegularizationRequest's existing convention) is enforced at the
 * DB layer rather than in Java after fetching everything.
 */
public final class AttendancePenaltySpecifications {

    private AttendancePenaltySpecifications() {}

    public static Specification<AttendancePenalty> employeeUserIdIn(Collection<UUID> employeeUserIds) {
        if (employeeUserIds == null || employeeUserIds.isEmpty()) return (root, query, cb) -> cb.disjunction();
        return (root, query, cb) -> root.get("employeeUserId").in(employeeUserIds);
    }

    public static Specification<AttendancePenalty> incidentDateBetween(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            if (from != null && to != null) return cb.between(root.get("incidentDate"), from, to);
            if (from != null) return cb.greaterThanOrEqualTo(root.get("incidentDate"), from);
            if (to != null) return cb.lessThanOrEqualTo(root.get("incidentDate"), to);
            return cb.conjunction();
        };
    }

    public static Specification<AttendancePenalty> statusEquals(String status) {
        if (status == null || status.isBlank()) return (root, query, cb) -> cb.conjunction();
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<AttendancePenalty> discrepancyTypeEquals(String discrepancyType) {
        if (discrepancyType == null || discrepancyType.isBlank()) return (root, query, cb) -> cb.conjunction();
        return (root, query, cb) -> cb.equal(root.get("discrepancyType"), discrepancyType);
    }
}

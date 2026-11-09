package com.nforce.onehr.service;

import com.nforce.onehr.entity.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * US-C12 — who may read an employee's payslip: {@code employeeId == currentUser.employeeId OR role
 * IN (HR_ADMIN, SUPER_ADMIN)}.
 *
 * <p><b>A Manager is deliberately NOT allowed</b>, unlike leave/expense/attendance where "a manager
 * sees their direct reports" is the norm in this app. A payslip is salary data; reporting to someone
 * is not a reason to see what they earn. This policy therefore never consults the reporting
 * hierarchy (no {@code DirectReportScopeService}), and a user who is both an employee's Manager and
 * has no HR role is refused exactly like any other colleague. Do not "fix" this to match the other
 * modules.
 */
@Component
public class PayslipAccessPolicy {

    private static final Set<String> PRIVILEGED_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    /** @throws AccessDeniedException (mapped to 403) unless {@code actor} is the payslip's owner or HR_ADMIN/SUPER_ADMIN. */
    public void requireCanView(User actor, UUID payslipEmployeeId) {
        if (actor.getId().equals(payslipEmployeeId)) {
            return;
        }
        boolean privileged = actor.getRoles().stream().anyMatch(r -> PRIVILEGED_ROLES.contains(r.getCode()));
        if (!privileged) {
            throw new AccessDeniedException("You are not allowed to view this payslip");
        }
    }
}

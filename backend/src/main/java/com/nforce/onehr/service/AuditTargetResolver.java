package com.nforce.onehr.service;

import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ExpenseClaimRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Best-effort human label for an audit-log target id. {@code audit_log.target_id} has no FK and
 * points at a different entity type depending on the action — this dispatches by action-prefix
 * to the relevant repository, degrading gracefully (never throws) since some call sites are
 * known to reuse the actor's own id as target_id (ASSET_*, LOGIN_*, PASSWORD_* — see
 * AssetService/AuthService: Asset.id is a Long and can't fit AuditService.log's UUID target slot).
 *
 * <p>A plain prefix dispatch is preferred over a pluggable strategy registry — the action set is
 * small (40 actions, 8 services) and unlikely to grow fast enough to need more abstraction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditTargetResolver {

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final ExpenseClaimRepository expenseClaimRepository;
    private final AttendanceRepository attendanceRepository;
    private final WebClockInRequestRepository webClockInRequestRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;

    /** Best-effort human label for a target UUID, given the action that produced it. Never throws. */
    public String resolve(String action, UUID targetId) {
        if (targetId == null) return "—";
        try {
            if (action == null) return fallback(targetId);
            if (action.startsWith("EMPLOYEE_") || action.startsWith("USER_") || action.equals("PASSWORD_RESET")) {
                return fallback(targetId);
            }
            if (action.startsWith("LEAVE_REQUEST_")) {
                return leaveRequestRepository.findById(targetId)
                        .map(r -> "Leave: " + employeeNameOrEmail(r.getEmployeeUserId()).orElse(shortId(r.getEmployeeUserId())))
                        .orElseGet(() -> fallback(targetId));
            }
            if (action.startsWith("EXPENSE_")) {
                return expenseClaimRepository.findById(targetId)
                        .map(c -> "Expense: " + employeeNameOrEmail(c.getEmployeeUserId()).orElse(shortId(c.getEmployeeUserId())))
                        .orElseGet(() -> fallback(targetId));
            }
            if (action.startsWith("ATTENDANCE_")) {
                return attendanceRepository.findById(targetId)
                        .map(a -> "Attendance: " + employeeNameOrEmail(a.getEmployeeUserId()).orElse(shortId(a.getEmployeeUserId())))
                        .orElseGet(() -> fallback(targetId));
            }
            if (action.startsWith("WEB_CLOCK_IN") || action.startsWith("WEB_CLOCK_OUT")) {
                return webClockInRequestRepository.findById(targetId)
                        .map(r -> "Web Clock-In: " + employeeNameOrEmail(r.getEmployeeUserId()).orElse(shortId(r.getEmployeeUserId())))
                        .orElseGet(() -> fallback(targetId));
            }
            if (action.startsWith("REGULARIZATION_")) {
                return regularizationRequestRepository.findById(targetId)
                        .map(r -> "Regularization: " + employeeNameOrEmail(r.getEmployeeUserId()).orElse(shortId(r.getEmployeeUserId())))
                        .orElseGet(() -> fallback(targetId));
            }
            // ASSET_*, LOGIN_*, PASSWORD_CHANGED, PASSWORD_CHANGE_FAILED, PASSWORD_RESET_VIA_FORGOT_FLOW:
            // target_id is a known data-quality placeholder (== actor_id) rather than a resolvable
            // domain entity — resolving it as a user is still accurate, just not asset-specific.
            return fallback(targetId);
        } catch (Exception e) {
            log.warn("Failed to resolve audit target label for action={} targetId={}", action, targetId, e);
            return shortId(targetId);
        }
    }

    /**
     * Resolves a free-text search fragment (name/email) to the set of target ids it could match,
     * for the "search by target" filter. Since most targets ultimately trace back to an employee,
     * this unions the direct employee/user id match with every domain-record id owned by a
     * matching employee.
     */
    public Set<UUID> resolveTargetIdsMatching(String query) {
        Set<UUID> matchingUsers = userRepository.findUserIdsByEmailOrFullNameContaining(query);
        if (matchingUsers.isEmpty()) return Set.of();

        Set<UUID> targetIds = new HashSet<>(matchingUsers);
        targetIds.addAll(leaveRequestRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        targetIds.addAll(expenseClaimRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        targetIds.addAll(attendanceRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        targetIds.addAll(webClockInRequestRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        targetIds.addAll(regularizationRequestRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        return targetIds;
    }

    private Optional<String> employeeNameOrEmail(UUID userId) {
        if (userId == null) return Optional.empty();
        return employeeRepository.findById(userId)
                .map(e -> e.getFullName())
                .or(() -> userRepository.findById(userId).map(u -> u.getEmail()));
    }

    private String fallback(UUID targetId) {
        return employeeNameOrEmail(targetId).orElseGet(() -> shortId(targetId));
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8) + "…";
    }
}

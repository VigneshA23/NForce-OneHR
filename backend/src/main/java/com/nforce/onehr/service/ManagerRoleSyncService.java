package com.nforce.onehr.service;

import com.nforce.onehr.entity.ManagerRoleAutoGrant;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.ManagerRoleAutoGrantRepository;
import com.nforce.onehr.repository.RoleRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the MANAGER role synced to actual reporting lines: granted the moment someone gains
 * their first direct report, revoked the moment their last one leaves — but ONLY for grants this
 * service itself made (tracked via {@link ManagerRoleAutoGrant}; see its own Javadoc). A Super
 * Admin's independent, pre-existing "set role to Manager" in User Management (which works
 * regardless of actual reports) is never touched by the revoke half of this, by design — see the
 * permission-system Phase 4 decision record.
 */
@Service
@RequiredArgsConstructor
public class ManagerRoleSyncService {

    private static final String MANAGER = "MANAGER";
    private static final String ROLE_AUTO_GRANTED = "ROLE_AUTO_GRANTED";
    private static final String ROLE_AUTO_REVOKED = "ROLE_AUTO_REVOKED";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final ManagerRoleAutoGrantRepository autoGrantRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    /**
     * Call right after a manager-history row is written assigning {@code managerId} as someone's
     * manager. A no-op if they already hold MANAGER, whether from a prior auto-grant or a manual
     * admin assignment — this never duplicates or overwrites an existing grant's provenance.
     */
    @Transactional
    public void grantIfNewManager(UUID managerId, UUID actorId) {
        if (managerId == null) return;
        User manager = userRepository.findById(managerId).orElse(null);
        if (manager == null) return;
        boolean alreadyManager = manager.getRoles().stream().anyMatch(r -> MANAGER.equals(r.getCode()));
        if (alreadyManager) return;

        Role managerRole = roleRepository.findByCode(MANAGER)
                .orElseThrow(() -> new IllegalStateException("MANAGER role not seeded"));
        manager.getRoles().add(managerRole);
        userRepository.save(manager);
        autoGrantRepository.save(ManagerRoleAutoGrant.builder().userId(managerId).build());

        auditService.log(actorId, ROLE_AUTO_GRANTED, managerId,
                auditSnapshot.toJson(Map.of("role", "none")),
                auditSnapshot.toJson(Map.of("role", MANAGER, "reason", "gained first direct report")));
    }

    /**
     * Call right after a manager-history row closing one of {@code managerId}'s direct-report
     * relationships is written (a reassignment away, or the report's own deactivation). A no-op
     * unless {@code managerId} has zero current direct reports left AND their MANAGER role is a
     * grant this service made (an auto-grant marker exists) — a manually-assigned MANAGER role is
     * never revoked, regardless of report count.
     */
    @Transactional
    public void revokeIfNoLongerManager(UUID managerId, UUID actorId) {
        if (managerId == null) return;
        if (!autoGrantRepository.existsById(managerId)) return;
        if (!historyRepository.findCurrentDirectReportIds(managerId).isEmpty()) return;

        User manager = userRepository.findById(managerId).orElse(null);
        if (manager == null) return;
        Role managerRole = roleRepository.findByCode(MANAGER).orElse(null);
        if (managerRole == null) return;

        manager.getRoles().remove(managerRole);
        userRepository.save(manager);
        autoGrantRepository.deleteById(managerId);

        auditService.log(actorId, ROLE_AUTO_REVOKED, managerId,
                auditSnapshot.toJson(Map.of("role", MANAGER)),
                auditSnapshot.toJson(Map.of("role", "none", "reason", "last direct report left")));
    }

    /**
     * Call while building the replacement role set of any operation that replaces a user's roles
     * wholesale (a Super Admin changing someone's primary role, or Manage Roles) — an
     * auto-granted MANAGER status reflects live reporting lines, not a primary-role choice, so it
     * survives that kind of reset. A no-op if this user holds no auto-grant marker.
     */
    @Transactional
    public void restoreIfAutoGranted(UUID userId, Set<Role> roles) {
        if (!isAutoGranted(userId)) return;
        roleRepository.findByCode(MANAGER).ifPresent(roles::add);
    }

    /** True if this user's MANAGER role is a grant this service made (and may later revoke). */
    @Transactional(readOnly = true)
    public boolean isAutoGranted(UUID userId) {
        return autoGrantRepository.existsById(userId);
    }
}

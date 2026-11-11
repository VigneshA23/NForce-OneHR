package com.nforce.onehr.security;

import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.RoleRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Break-glass recovery so the organisation can never be locked out of Roles and Permissions.
 *
 * <p>The application already refuses to remove the last active Super Admin. If every Super Admin is
 * nevertheless gone (direct database edits, a bad migration, accounts deactivated in bulk), start the
 * application once with {@code ONEHR_RECOVERY_SUPER_ADMIN_EMAIL=<an existing active user's email>}. When,
 * and only when, there is NO active Super Admin, that user is granted the role and an audited
 * {@code ROLE_SUPER_ADMIN_RECOVERED} entry is written. With any active Super Admin present it does nothing, so
 * leaving the variable set is harmless; remove it after recovery all the same. Disabled (empty) by default.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SuperAdminRecoveryRunner implements ApplicationRunner {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Value("${app.recovery.super-admin-email:}")
    private String recoveryEmail;

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    /** @return true when the role was granted. Package-visible so tests call it directly. */
    @Transactional
    boolean recover() {
        if (recoveryEmail == null || recoveryEmail.isBlank()) return false;
        if (!userRepository.findActiveSuperAdmins().isEmpty()) {
            log.info("Super Admin recovery requested but an active Super Admin exists, nothing to do. Remove ONEHR_RECOVERY_SUPER_ADMIN_EMAIL.");
            return false;
        }
        User user = userRepository.findByEmail(recoveryEmail.trim()).orElse(null);
        if (user == null || !user.isActive() || user.getDeletedAt() != null) {
            log.error("Super Admin recovery: {} is not an active user, no Super Admin was restored.", recoveryEmail);
            return false;
        }
        Role role = roleRepository.findByCode(SUPER_ADMIN).orElse(null);
        if (role == null) {
            log.error("Super Admin recovery: the SUPER_ADMIN role does not exist.");
            return false;
        }
        List<String> before = user.getRoles().stream().map(Role::getCode).sorted().toList();
        user.getRoles().add(role);
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setTokenVersionReason("PROFILE_UPDATED");
        userRepository.save(user);
        auditService.log(user.getId(), "ROLE_SUPER_ADMIN_RECOVERED", user.getId(),
                auditSnapshot.toJson(Map.of("roles", before)),
                auditSnapshot.toJson(Map.of("roles", user.getRoles().stream().map(Role::getCode).sorted().toList())));
        log.warn("Super Admin recovery: granted {} to {}. Remove ONEHR_RECOVERY_SUPER_ADMIN_EMAIL now.", SUPER_ADMIN, user.getEmail());
        return true;
    }
}

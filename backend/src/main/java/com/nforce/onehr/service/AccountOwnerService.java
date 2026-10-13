package com.nforce.onehr.service;

import com.nforce.onehr.entity.PermissionModule;
import com.nforce.onehr.entity.SuperAdminModuleFence;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.PermissionModuleRepository;
import com.nforce.onehr.repository.SuperAdminModuleFenceRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Only the Account Owner may change which (module, action) pairs are fenced off from Super
 * Admin — not a role or a role_permissions grant (an Account Owner holding SUPER_ADMIN must not
 * be able to un-fence their own way around the ceiling just by having the right role), so this
 * is a plain {@code isAccountOwner} check on the actor, independent of the permission engine.
 */
@Service
@RequiredArgsConstructor
public class AccountOwnerService {

    private final UserRepository userRepository;
    private final PermissionModuleRepository moduleRepository;
    private final SuperAdminModuleFenceRepository fenceRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional(readOnly = true)
    public List<SuperAdminModuleFence> listFences(String actorEmail) {
        requireAccountOwner(actorEmail);
        return fenceRepository.findAllWithModule();
    }

    @Transactional
    public SuperAdminModuleFence fenceModule(String moduleCode, String action, String actorEmail) {
        User owner = requireAccountOwner(actorEmail);
        PermissionModule module = moduleRepository.findByCode(moduleCode)
                .orElseThrow(() -> new NoSuchElementException("Module not found: " + moduleCode));
        if (fenceRepository.findByModule_CodeAndAction(moduleCode, action).isPresent()) {
            throw new IllegalArgumentException(moduleCode + ":" + action + " is already fenced");
        }
        SuperAdminModuleFence fence = fenceRepository.save(SuperAdminModuleFence.builder()
                .module(module)
                .action(action)
                .fencedBy(owner.getId())
                .build());
        auditService.log(owner.getId(), "MODULE_FENCE_APPLIED", owner.getId(),
                auditSnapshot.toJson(Map.of("fenced", false)),
                auditSnapshot.toJson(Map.of("fenced", true, "module", moduleCode, "action", action)));
        return fence;
    }

    @Transactional
    public void unfenceModule(String moduleCode, String action, String actorEmail) {
        User owner = requireAccountOwner(actorEmail);
        SuperAdminModuleFence fence = fenceRepository.findByModule_CodeAndAction(moduleCode, action)
                .orElseThrow(() -> new NoSuchElementException(moduleCode + ":" + action + " is not fenced"));
        fenceRepository.delete(fence);
        // "Super Admin access applies immediately" — nothing else to do here: EffectivePermissionService
        // re-resolves from this table on every single request (same as every other permission
        // check in this app), so the very next API call already reflects the lifted fence.
        auditService.log(owner.getId(), "MODULE_FENCE_LIFTED", owner.getId(),
                auditSnapshot.toJson(Map.of("fenced", true, "module", moduleCode, "action", action)),
                auditSnapshot.toJson(Map.of("fenced", false)));
    }

    private User requireAccountOwner(String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + actorEmail));
        if (!actor.isAccountOwner()) {
            throw new AccessDeniedException("Only the Account Owner can manage Super Admin module fences");
        }
        return actor;
    }
}

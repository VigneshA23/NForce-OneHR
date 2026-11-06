package com.nforce.onehr.controller;

import com.nforce.onehr.dto.MyPermissionsResponse;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.util.RoleUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * The frontend's one source of truth for "what can I do" — no equivalent existed before this
 * (login only ever returns a single primaryRole string, computed the same way as here, at
 * login time only — see AuthService/LoginResponse). Drives nav/route/action-button gating,
 * replacing nav.config.ts's static per-role array (see that file's own note on this).
 */
@RestController
@RequiredArgsConstructor
public class MeController {

    private final UserRepository userRepository;
    private final EffectivePermissionService effectivePermissionService;

    @GetMapping("/api/me/permissions")
    public MyPermissionsResponse myPermissions(Authentication auth) {
        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new NoSuchElementException("User not found: " + auth.getName()));

        List<String> roleCodes = user.getRoles().stream().map(Role::getCode).sorted().toList();
        String primaryRole = RoleUtils.primaryRoleCode(user.getRoles(), "EMPLOYEE");

        return new MyPermissionsResponse(primaryRole, roleCodes,
                effectivePermissionService.effectivePermissions(user.getRoles()),
                effectivePermissionService.effectivePermissions(
                        user.getRoles().stream().filter(r -> !r.isSystem()).toList()));
    }
}

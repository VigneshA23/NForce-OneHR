package com.nforce.onehr.controller;

import com.nforce.onehr.dto.MyPermissionsResponse;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.dto.AccessCatalogueResponse;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.PermissionCatalogue;
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
    private final PermissionCatalogue permissionCatalogue;

    /**
     * Which pages need which permission, from permission-catalogue.yml, so the sidebar and the route guard
     * read the same definition the backend ships rather than a hand-copied table. Carries no role or user
     * data and nothing sensitive, so it is open to every signed-in user (the page lists are not secrets; the
     * data behind them is still enforced per endpoint).
     */
    @GetMapping("/api/me/access-catalogue")
    public AccessCatalogueResponse accessCatalogue() {
        return new AccessCatalogueResponse(permissionCatalogue.routes().stream()
                .map(r -> new AccessCatalogueResponse.RouteRequirement(r.path(), r.requires())).toList());
    }

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

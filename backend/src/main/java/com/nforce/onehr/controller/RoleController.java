package com.nforce.onehr.controller;

import com.nforce.onehr.dto.roles.RoleDtos.*;
import com.nforce.onehr.service.RoleAdminService;
import com.nforce.onehr.service.UserManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Roles & Permissions administration. Every endpoint — reads included — is permission-gated here;
 * the finer rules (system-role protection, privilege ceiling, last Super Admin, no self-change) are
 * enforced in RoleAdminService / UserManagementService, so they hold no matter which endpoint or
 * client reaches them.
 */
@RestController
@RequiredArgsConstructor
public class RoleController {

    private final RoleAdminService roleAdminService;
    private final UserManagementService userManagementService;

    @GetMapping("/api/roles")
    @PreAuthorize("hasAuthority('roles:VIEW:ALL')")
    public List<RoleSummary> list() {
        return roleAdminService.list();
    }

    @GetMapping("/api/roles/{roleId}")
    @PreAuthorize("hasAuthority('roles:VIEW:ALL')")
    public RoleDetail get(@PathVariable Integer roleId) {
        return roleAdminService.get(roleId);
    }

    @GetMapping("/api/permissions/catalogue")
    @PreAuthorize("hasAuthority('roles:VIEW:ALL')")
    public List<CatalogueModule> catalogue() {
        return roleAdminService.catalogue();
    }

    @PostMapping("/api/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('roles:CREATE:ALL')")
    public RoleDetail create(@Valid @RequestBody CreateRoleRequest req, Principal principal) {
        return roleAdminService.create(req, principal.getName());
    }

    @PutMapping("/api/roles/{roleId}")
    @PreAuthorize("hasAuthority('roles:EDIT:ALL')")
    public RoleDetail update(@PathVariable Integer roleId, @Valid @RequestBody UpdateRoleRequest req, Principal principal) {
        return roleAdminService.update(roleId, req, principal.getName());
    }

    @PatchMapping("/api/roles/{roleId}/status")
    @PreAuthorize("hasAuthority('roles:EDIT:ALL')")
    public RoleDetail setStatus(@PathVariable Integer roleId, @Valid @RequestBody UpdateRoleStatusRequest req, Principal principal) {
        return roleAdminService.setActive(roleId, req.active(), req.version(), principal.getName());
    }

    @PutMapping("/api/roles/{roleId}/permissions")
    @PreAuthorize("hasAuthority('roles:CONFIGURE:ALL')")
    public RoleDetail setPermissions(@PathVariable Integer roleId, @Valid @RequestBody UpdateRolePermissionsRequest req,
                                     Principal principal) {
        return roleAdminService.setPermissions(roleId, req.grants(), req.version(), principal.getName());
    }

    @DeleteMapping("/api/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('roles:DELETE:ALL')")
    public void delete(@PathVariable Integer roleId, @RequestParam(defaultValue = "false") boolean removeFromUsers,
                       Principal principal) {
        roleAdminService.delete(roleId, removeFromUsers, principal.getName());
    }

    @PostMapping("/api/roles/{roleId}/members")
    @PreAuthorize("hasAuthority('user_roles:EDIT:ALL')")
    public RoleDetail addMember(@PathVariable Integer roleId, @Valid @RequestBody AddRoleMemberRequest req, Principal principal) {
        return roleAdminService.addMember(roleId, req.userId(), principal.getName());
    }

    @DeleteMapping("/api/roles/{roleId}/members/{userId}")
    @PreAuthorize("hasAuthority('user_roles:EDIT:ALL')")
    public RoleDetail removeMember(@PathVariable Integer roleId, @PathVariable UUID userId, Principal principal) {
        return roleAdminService.removeMember(roleId, userId, principal.getName());
    }

    @GetMapping("/api/users/{userId}/roles")
    @PreAuthorize("hasAuthority('user_roles:VIEW:ALL')")
    public UserRolesResponse userRoles(@PathVariable UUID userId) {
        return userManagementService.getUserRoles(userId);
    }

    @PutMapping("/api/users/{userId}/roles")
    @PreAuthorize("hasAuthority('user_roles:EDIT:ALL')")
    public UserRolesResponse setUserRoles(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRolesRequest req,
                                          Principal principal) {
        return userManagementService.setUserRoles(userId, req.roleIds(), principal.getName());
    }
}

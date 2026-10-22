package com.nforce.onehr.dto.roles;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Roles & Permissions administration shapes — see RoleAdminService / RoleController. */
public final class RoleDtos {

    private RoleDtos() {}

    /** One (module, action, scope) grant — the same triple a role_permissions row stores. */
    public record Grant(@NotBlank String module, @NotBlank String action, @NotBlank String scope) {}

    /** Only name/description/grants are accepted — code, is_system and status are never client-settable. */
    public record CreateRoleRequest(
            @NotBlank @Size(min = 2, max = 50) String name,
            @Size(max = 255) String description,
            @Valid List<Grant> grants) {}

    /** {@code version}: the RoleSummary.version the client loaded — a stale one is rejected (409). */
    public record UpdateRoleRequest(
            @NotBlank @Size(min = 2, max = 50) String name,
            @Size(max = 255) String description,
            @NotNull Integer version) {}

    public record UpdateRolePermissionsRequest(@NotNull @Valid List<Grant> grants, @NotNull Integer version) {}

    public record UpdateRoleStatusRequest(@NotNull Boolean active, @NotNull Integer version) {}

    public record UpdateUserRolesRequest(@NotNull List<Integer> roleIds) {}

    public record AddRoleMemberRequest(@NotNull UUID userId) {}

    /**
     * @param permissionsEditable false for SUPER_ADMIN (locked) — tells the UI up front instead of
     *                            letting a save fail.
     * @param metadataEditable    false for every system role (name/description/status are fixed).
     */
    public record RoleSummary(Integer id, String code, String name, String description, boolean system,
                              boolean active, boolean permissionsEditable, boolean metadataEditable,
                              long userCount, long permissionCount, LocalDateTime createdAt,
                              LocalDateTime updatedAt, int version) {}

    public record RoleMember(UUID userId, String fullName, String email, String employeeCode, boolean active) {}

    public record RoleDetail(RoleSummary role, List<Grant> grants, List<RoleMember> members) {}

    /** @param configurable see modules.custom_role_ready (V232) — false means read-only in the editor. */
    public record CatalogueModule(String code, String name, boolean configurable, List<String> actions) {}

    public record RoleRef(Integer id, String code, String name, boolean system, boolean active,
                          boolean autoGranted) {}

    public record EffectiveGrant(String module, String moduleName, String action, String scope, List<String> sources) {}

    public record UserRolesResponse(UUID userId, String fullName, String email, List<RoleRef> roles,
                                    List<EffectiveGrant> effective) {}
}

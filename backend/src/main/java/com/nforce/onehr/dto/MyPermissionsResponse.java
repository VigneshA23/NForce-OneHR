package com.nforce.onehr.dto;

import lombok.Value;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Drives the frontend's nav/route/action-button gating (GET /api/me/permissions). */
@Value
public class MyPermissionsResponse {
    String primaryRole;
    List<String> roles;
    Map<String, Map<String, Set<String>>> permissions;
    // The subset granted by custom (non-system) roles. The sidebar is still chosen by primaryRole
    // (curated per system role), so this is how a custom role's grants surface extra nav items
    // without reshaping any system role's curated nav — see frontend permissions.ts.
    Map<String, Map<String, Set<String>>> customRolePermissions;
}

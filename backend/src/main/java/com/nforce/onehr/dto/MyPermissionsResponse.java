package com.nforce.onehr.dto;

import lombok.Value;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Drives the frontend's nav/route/action-button gating (GET /api/me/permissions). */
@Value
public class MyPermissionsResponse {
    UUID userId;
    String primaryRole;
    List<String> roles;
    boolean accountOwner;
    Map<String, Map<String, Set<String>>> permissions;
}

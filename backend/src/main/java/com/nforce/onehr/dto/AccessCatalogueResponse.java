package com.nforce.onehr.dto;

import java.util.List;

/** Pages that need a permission: open when the user holds at least one of {@code requires} (module:ACTION:SCOPE). */
public record AccessCatalogueResponse(List<RouteRequirement> routes) {
    public record RouteRequirement(String path, List<String> requires) {}
}

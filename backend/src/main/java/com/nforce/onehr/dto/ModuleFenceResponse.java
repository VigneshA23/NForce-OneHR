package com.nforce.onehr.dto;

import com.nforce.onehr.entity.SuperAdminModuleFence;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class ModuleFenceResponse {
    Long id;
    String module;
    String moduleDisplayName;
    String action;
    LocalDateTime fencedAt;
    UUID fencedBy;

    public static ModuleFenceResponse from(SuperAdminModuleFence fence) {
        return new ModuleFenceResponse(
                fence.getId(),
                fence.getModule().getCode(),
                fence.getModule().getDisplayName(),
                fence.getAction(),
                fence.getFencedAt(),
                fence.getFencedBy());
    }
}

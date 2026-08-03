package com.nforce.onehr.dto.asset;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignAssetRequest {
    @NotNull private UUID employeeUserId;
}

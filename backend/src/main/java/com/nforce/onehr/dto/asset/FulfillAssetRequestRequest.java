package com.nforce.onehr.dto.asset;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FulfillAssetRequestRequest {
    @NotNull private Long assetId;
}

package com.nforce.onehr.dto.asset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SubmitAssetRequestRequest {
    @NotNull  private Integer categoryId;
    @NotBlank private String reason;
}

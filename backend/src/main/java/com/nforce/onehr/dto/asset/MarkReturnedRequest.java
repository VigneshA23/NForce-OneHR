package com.nforce.onehr.dto.asset;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MarkReturnedRequest {
    @NotBlank private String returnCondition;
}

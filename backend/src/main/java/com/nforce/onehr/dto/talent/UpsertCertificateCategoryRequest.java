package com.nforce.onehr.dto.talent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpsertCertificateCategoryRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    private boolean hasExpiry = true;
}

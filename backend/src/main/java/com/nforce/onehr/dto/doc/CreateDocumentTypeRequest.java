package com.nforce.onehr.dto.doc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateDocumentTypeRequest {

    @NotBlank
    @Size(max = 80)
    private String name;

    private boolean requiresVerification = true;
    private boolean requiresExpiryDate = false;
    private String applicableEmploymentTypes;
    private String applicableLocations;
}

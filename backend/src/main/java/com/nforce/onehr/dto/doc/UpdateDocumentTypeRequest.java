package com.nforce.onehr.dto.doc;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDocumentTypeRequest {

    @Size(max = 80)
    private String name;

    private Boolean requiresVerification;
    private Boolean requiresExpiryDate;
    private String applicableEmploymentTypes;
    private String applicableLocations;
}

package com.nforce.onehr.dto.doc;

import com.nforce.onehr.entity.DocumentType;
import lombok.Value;

import java.time.Instant;

@Value
public class DocumentTypeResponse {
    int id;
    String name;
    boolean requiresVerification;
    boolean requiresExpiryDate;
    String applicableEmploymentTypes;
    String applicableLocations;
    boolean active;
    Instant createdAt;
    long usageCount;

    public static DocumentTypeResponse from(DocumentType dt, long usageCount) {
        return new DocumentTypeResponse(
                dt.getId(), dt.getName(), dt.isRequiresVerification(), dt.isRequiresExpiryDate(),
                dt.getApplicableEmploymentTypes(), dt.getApplicableLocations(),
                dt.isActive(), dt.getCreatedAt(), usageCount);
    }
}

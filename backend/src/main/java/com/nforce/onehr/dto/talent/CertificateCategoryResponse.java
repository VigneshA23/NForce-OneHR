package com.nforce.onehr.dto.talent;

import com.nforce.onehr.entity.CertificateCategory;
import lombok.Value;

import java.util.UUID;

@Value
public class CertificateCategoryResponse {
    UUID id;
    String name;
    boolean hasExpiry;
    boolean active;
    long usageCount;

    public static CertificateCategoryResponse from(CertificateCategory c, long usageCount) {
        return new CertificateCategoryResponse(c.getId(), c.getName(), c.isHasExpiry(), c.isActive(), usageCount);
    }
}

package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.BusinessUnit;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class BusinessUnitResponse {
    UUID id;
    String name;
    boolean active;
    long employeeCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static BusinessUnitResponse from(BusinessUnit b, long employeeCount) {
        return new BusinessUnitResponse(b.getId(), b.getName(), b.isActive(), employeeCount, b.getCreatedAt(), b.getUpdatedAt());
    }
}

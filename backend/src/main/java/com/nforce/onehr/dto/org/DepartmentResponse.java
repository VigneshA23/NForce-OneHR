package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.Department;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class DepartmentResponse {
    UUID id;
    String name;
    boolean active;
    long employeeCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static DepartmentResponse from(Department d, long employeeCount) {
        return new DepartmentResponse(d.getId(), d.getName(), d.isActive(), employeeCount, d.getCreatedAt(), d.getUpdatedAt());
    }
}

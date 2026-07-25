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
    LocalDateTime createdAt;

    public static DepartmentResponse from(Department d) {
        return new DepartmentResponse(d.getId(), d.getName(), d.isActive(), d.getCreatedAt());
    }
}

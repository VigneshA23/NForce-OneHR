package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.Designation;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class DesignationResponse {
    UUID id;
    String title;
    String grade;
    String level;
    UUID departmentId;
    String departmentName;
    boolean active;
    long employeeCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static DesignationResponse from(Designation d, long employeeCount, String departmentName) {
        return new DesignationResponse(d.getId(), d.getTitle(), d.getGrade(), d.getLevel(), d.getDepartmentId(), departmentName,
                d.isActive(), employeeCount, d.getCreatedAt(), d.getUpdatedAt());
    }
}

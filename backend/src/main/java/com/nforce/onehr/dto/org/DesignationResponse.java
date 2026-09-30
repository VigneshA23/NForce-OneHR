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
    boolean active;
    long employeeCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static DesignationResponse from(Designation d, long employeeCount) {
        return new DesignationResponse(d.getId(), d.getTitle(), d.getGrade(), d.getLevel(), d.isActive(), employeeCount, d.getCreatedAt(), d.getUpdatedAt());
    }
}

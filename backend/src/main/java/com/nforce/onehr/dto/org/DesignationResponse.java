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
    boolean active;
    LocalDateTime createdAt;

    public static DesignationResponse from(Designation d) {
        return new DesignationResponse(d.getId(), d.getTitle(), d.getGrade(), d.isActive(), d.getCreatedAt());
    }
}

package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data @Builder
public class ProfileChangeRequestResponse {
    private UUID id;
    private UUID employeeUserId;
    private String employeeName;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    /** Masked in lists; raw only from the audited detail endpoint ({@code unmasked == true}). */
    private boolean unmasked;
    private List<ProfileChangeFieldDto> fields;
}

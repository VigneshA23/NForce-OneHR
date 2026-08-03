package com.nforce.onehr.dto.asset;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class AssetRequestResponse {
    private Long id;
    private UUID employeeUserId;
    private String employeeName;
    private Integer categoryId;
    private String categoryName;
    private String reason;
    private String status;
    private String managerDecidedByName;
    private Instant managerDecidedAt;
    private String fulfilledByName;
    private Instant fulfilledAt;
    private String rejectionReason;
    private Instant createdAt;
}

package com.nforce.onehr.dto.asset;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class AssetAssignmentResponse {
    private Long id;
    private Long assetId;
    private String assetTag;
    private String categoryName;
    private String brand;
    private String model;
    private UUID employeeUserId;
    private String employeeName;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private Instant acknowledgedAt;
    private String returnCondition;
    private String condition;
}

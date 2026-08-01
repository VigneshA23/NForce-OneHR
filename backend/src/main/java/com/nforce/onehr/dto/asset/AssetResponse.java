package com.nforce.onehr.dto.asset;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder
public class AssetResponse {
    private Long id;
    private String assetTag;
    private Integer categoryId;
    private String categoryName;
    private String brand;
    private String model;
    private String serialNumber;
    private LocalDate purchaseDate;
    private BigDecimal purchaseCost;
    private LocalDate warrantyExpiry;
    private String condition;
    private String status;
    private java.util.UUID locationId;
    private String locationName;
    // Populated when status = ASSIGNED
    private String currentCustodianName;
    private String currentCustodianUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

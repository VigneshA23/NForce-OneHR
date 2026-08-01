package com.nforce.onehr.dto.asset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreateAssetRequest {
    @NotBlank private String assetTag;
    @NotNull  private Integer categoryId;
    private String brand;
    private String model;
    private String serialNumber;
    private LocalDate purchaseDate;
    private BigDecimal purchaseCost;
    private LocalDate warrantyExpiry;
    private String condition;
    private java.util.UUID locationId;
}

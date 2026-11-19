package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
public class PtRegisteredLocationResponse {
    private UUID id;
    private UUID payGroupId;
    private String stateName;
    private String locationName;
    private String establishmentId;
    private LocalDate registrationDate;
    private String signatory;
    private String signatoryDesignation;
    private String signatoryFatherName;
}

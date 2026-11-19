package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class PayGroupFilingDetailsResponse {
    private UUID payGroupId;
    private String pan;
    private String tan;
    private String tanCircleNumber;
    private String citTdsLocation;
    private String form130Signatory;
    private String signatoryDesignation;
    private String signatoryFatherName;
    private String pfEstablishmentId;
    private String esiEstablishmentId;
    private LocalDateTime updatedAt;
}

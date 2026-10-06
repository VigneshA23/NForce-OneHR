package com.nforce.onehr.dto.certificates;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class EmployeeCertificateRequest {
    @NotBlank private String name;
    private String issuingOrganization;
    private String credentialId;
    private String credentialUrl;
    private LocalDate issueDate;
    private LocalDate expiryDate;
}

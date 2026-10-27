package com.nforce.onehr.dto.certificates;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class EmployeeCertificateRequest {
    @NotBlank private String name;
    // Optional, unlike EmployeeSkillRequest's skillId — the spec only requires the category to be
    // chosen from the master list when one IS chosen; certificate name itself stays free text.
    private UUID categoryId;
    private String issuingOrganization;
    private String credentialId;
    private String credentialUrl;
    private LocalDate issueDate;
    private LocalDate expiryDate;
}

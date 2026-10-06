package com.nforce.onehr.dto.certificates;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class EmployeeCertificateResponse {
    private UUID id;
    private String name;
    private String issuingOrganization;
    private String credentialId;
    private String credentialUrl;
    private LocalDate issueDate;
    private LocalDate expiryDate;
    private Instant createdAt;
    private Instant updatedAt;
}

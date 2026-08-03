package com.nforce.onehr.dto.doc;

import com.nforce.onehr.entity.EmployeeDocument;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Value
public class EmployeeDocumentResponse {
    UUID id;
    UUID employeeUserId;
    String employeeName;
    int documentTypeId;
    String documentTypeName;
    boolean requiresVerification;
    boolean requiresExpiryDate;
    String fileName;
    String fileUrl;
    LocalDate issueDate;
    LocalDate expiryDate;
    String status;
    UUID verifiedBy;
    Instant verifiedAt;
    String rejectionReason;
    Instant uploadedAt;
    Instant updatedAt;

    public static EmployeeDocumentResponse from(EmployeeDocument d) {
        return from(d, null);
    }

    public static EmployeeDocumentResponse from(EmployeeDocument d, String employeeName) {
        return new EmployeeDocumentResponse(
                d.getId(), d.getEmployeeUserId(), employeeName,
                d.getDocumentType().getId(), d.getDocumentType().getName(),
                d.getDocumentType().isRequiresVerification(), d.getDocumentType().isRequiresExpiryDate(),
                d.getFileName(), d.getFileUrl(),
                d.getIssueDate(), d.getExpiryDate(),
                d.getStatus(), d.getVerifiedBy(), d.getVerifiedAt(),
                d.getRejectionReason(), d.getUploadedAt(), d.getUpdatedAt());
    }
}

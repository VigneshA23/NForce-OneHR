package com.nforce.onehr.dto.doc;

import lombok.Value;

@Value
public class DocumentAdminKpiDto {
    long pendingVerification;
    long employeesWithPending;
    long expiringWithin30Days;
    long totalDocuments;
}

package com.nforce.onehr.dto.doc;

import lombok.Value;

@Value
public class RequiredDocumentDto {
    int documentTypeId;
    String documentTypeName;
    boolean requiresVerification;
    boolean requiresExpiryDate;
    boolean uploaded;
    String status;
    boolean expiringSoon;
}

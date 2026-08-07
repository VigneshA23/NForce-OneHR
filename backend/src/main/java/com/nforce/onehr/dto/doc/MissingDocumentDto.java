package com.nforce.onehr.dto.doc;

import lombok.Value;

import java.util.UUID;

@Value
public class MissingDocumentDto {
    UUID employeeUserId;
    String employeeName;
    int documentTypeId;
    String documentTypeName;
}

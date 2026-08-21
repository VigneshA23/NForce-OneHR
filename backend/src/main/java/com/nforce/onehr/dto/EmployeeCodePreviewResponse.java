package com.nforce.onehr.dto;

import lombok.Value;

/** Read-only preview of the Employee ID that would be assigned right now — see EmployeeCodeGenerator#preview. */
@Value
public class EmployeeCodePreviewResponse {
    String employeeCode;
}

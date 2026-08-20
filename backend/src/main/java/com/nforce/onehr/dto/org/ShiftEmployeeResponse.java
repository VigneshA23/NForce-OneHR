package com.nforce.onehr.dto.org;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/** One row of the "employees assigned to this shift" drill-down (Organization Masters → Shifts). */
@Value
@Builder
public class ShiftEmployeeResponse {
    UUID userId;
    String employeeCode;
    String fullName;
    String email;
    String departmentName;
}

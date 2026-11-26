package com.nforce.onehr.service.assignment;

import java.util.UUID;

/** The employee attributes assignment rules can match on, resolved for one date. */
public record EmployeeFacts(UUID employeeUserId, UUID departmentId, UUID locationId, UUID shiftId,
                            String employmentType, String grade) {
}

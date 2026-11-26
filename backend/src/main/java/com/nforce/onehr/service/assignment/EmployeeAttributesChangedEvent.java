package com.nforce.onehr.service.assignment;

import java.util.UUID;

/** PN-04: published after an employee's department / location / shift / employment type / designation (grade) changes or the employee is created. */
public record EmployeeAttributesChangedEvent(UUID employeeUserId) {
}

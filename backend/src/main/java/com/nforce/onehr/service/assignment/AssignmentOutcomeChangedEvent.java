package com.nforce.onehr.service.assignment;

import java.time.LocalDate;
import java.util.UUID;

/** The rules' recorded outcome for one employee in one domain changed, effective {@code date} (LV-01). */
public record AssignmentOutcomeChangedEvent(String domain, UUID employeeUserId, LocalDate date) {
}

package com.nforce.onehr.dto.onboarding;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Onboarding always starts for an employee that already exists in Employee
 * Master — this is a checklist/orchestration layer, not another way to create
 * employee records. Use POST /api/employees first if the person isn't in the
 * system yet.
 */
@Data
public class StartOnboardingRequest {

    @NotNull
    private UUID employeeUserId;
}

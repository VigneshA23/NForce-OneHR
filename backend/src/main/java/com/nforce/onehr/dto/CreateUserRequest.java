package com.nforce.onehr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

// Super Admin scope — role field included, all 4 Phase 1 roles allowed.
@Data
public class CreateUserRequest {
    @NotBlank
    @Pattern(regexp = "^(?=.*\\p{L})[\\p{L}\\s'-]+$", message = "Full name can only contain letters, spaces, hyphens, and apostrophes")
    private String fullName;

    // @Email alone accepts things like "a@99999999999" or "a@example.com123" — it has no
    // opinion on the TLD. The explicit pattern enforces a real, letters-only TLD with nothing
    // trailing it, per the reported business rule.
    @NotBlank
    @Email
    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Enter a valid email address with a proper domain (e.g. name@company.com)")
    private String email;

    // EMPLOYEE | MANAGER | HR_ADMIN | SUPER_ADMIN
    @NotBlank
    private String role;

    private String employeeCode;
    private UUID businessUnitId;
    private UUID departmentId;
    private UUID designationId;
    private UUID shiftId;

    // Required whenever shiftId is set (validated in UserManagementService#createUser, not here,
    // since it's conditional on shiftId rather than always-mandatory) — the exact date the admin
    // chose in the Effective From picker. Today and any future date are valid; a past date is
    // rejected. Never derived from joiningDate or "next working day" — the user's own explicit
    // choice is the sole source, matching EmployeeShiftAssignment.effectiveFrom's own contract.
    private LocalDate effectiveFrom;

    @NotNull
    private UUID locationId;

    private String employmentType = "FULL_TIME";
    private String workMode = "ONSITE";

    @NotNull
    private LocalDate joiningDate;

    private UUID managerId;

    // Deliberately NO timezone field — see CreateEmployeeRequest's identical comment. locationId
    // above (mandatory here) is the only timezone-relevant input.
}

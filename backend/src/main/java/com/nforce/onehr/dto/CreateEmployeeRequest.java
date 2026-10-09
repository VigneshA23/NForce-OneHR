package com.nforce.onehr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateEmployeeRequest {
    @NotBlank
    @Pattern(regexp = "^(?=.*\\p{L})[\\p{L}\\s'-]+$", message = "Full name can only contain letters, spaces, hyphens, and apostrophes")
    private String fullName;

    // See CreateUserRequest — @Email alone doesn't enforce a real, letters-only TLD.
    @NotBlank
    @Email
    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Enter a valid email address with a proper domain (e.g. name@company.com)")
    private String email;

    private String employeeCode;

    private UUID businessUnitId;
    private UUID departmentId;
    private UUID designationId;
    private UUID locationId;

    private String employmentType = "FULL_TIME";
    private String workMode = "ONSITE";

    @NotNull
    private LocalDate joiningDate;

    private UUID managerId;

    // Code-review corrective pass, finding 10: optional, added so this path can support the same
    // initial Shift assignment UserManagementService#createUser already offers — omitting it
    // (the pre-existing behavior for every caller before this field existed) still leaves the
    // employee shift-less, exactly as before. See EmployeeService#createEmployee's own comment
    // for how a selected Shift here becomes an EmployeeShiftAssignment.
    private UUID shiftId;

    // Required whenever shiftId is set (validated in EmployeeService#createEmployee) — the exact
    // date the admin chose in the Effective From picker. Today and any future date are valid; a
    // past date is rejected. Never derived from joiningDate or "next working day."
    private LocalDate effectiveFrom;

    // Deliberately NO timezone field — the finalized Location/Timezone model derives an
    // employee's effective attendance timezone entirely from their assigned Location (see
    // Employee's own class Javadoc). locationId above is the only timezone-relevant input; an
    // invalid, inactive, or timezone-less Location is rejected server-side rather than silently
    // falling back to something else — see EmployeeService#validateAssignableLocation.
}

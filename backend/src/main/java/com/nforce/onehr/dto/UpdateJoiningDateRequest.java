package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

// Super Admin only — see UserManagementController. Note captures the reason for the
// correction/change and is stored in the audit trail alongside the old/new dates.
@Data
public class UpdateJoiningDateRequest {
    @NotNull
    private LocalDate newJoiningDate;
    private String note;
}

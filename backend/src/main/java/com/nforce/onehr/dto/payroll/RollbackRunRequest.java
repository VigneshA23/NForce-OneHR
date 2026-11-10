package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** US-C27 — whole-run rollback only in this increment; see PayrollRun's own doc comment. */
@Getter
@Setter
public class RollbackRunRequest {

    @NotBlank
    private String reason;
}

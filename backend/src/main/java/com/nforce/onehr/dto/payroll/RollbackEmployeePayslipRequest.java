package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RollbackEmployeePayslipRequest {

    @NotBlank
    private String reason;
}

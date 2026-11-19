package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Centralized "Distribute Payslips" action on the Payslips tab — one call for the whole run, not
 * one per employee. {@code employeeIds} empty or null means every payslip in the run. */
@Getter
@Setter
public class DistributePayslipsRequest {

    private List<UUID> employeeIds;

    @NotEmpty(message = "Select at least one distribution channel")
    private Set<@Pattern(regexp = "PORTAL|EMAIL", message = "channel must be PORTAL or EMAIL") String> channels;
}

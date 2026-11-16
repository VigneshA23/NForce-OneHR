package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.EmployeeFinancialDetailsResponse;
import com.nforce.onehr.dto.payroll.UpdateEmployeeFinancialDetailsRequest;
import com.nforce.onehr.service.EmployeeFinancialDetailsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

/** Keka restyle (payroll module) — §3.4 "Import Financial Information". Deliberately gated on
 * payroll:CONFIGURE:ALL, not the Employee module's own employees:EDIT:ALL — this is specifically
 * the Payroll Admin capability Keka places under Payroll Admin → Operations, not a general
 * employee-record edit. */
@RestController
@RequestMapping("/api/payroll/employees/{employeeId}/financial-details")
@RequiredArgsConstructor
public class EmployeeFinancialDetailsController {

    private final EmployeeFinancialDetailsService employeeFinancialDetailsService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public EmployeeFinancialDetailsResponse get(@PathVariable UUID employeeId) {
        return employeeFinancialDetailsService.get(employeeId);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public EmployeeFinancialDetailsResponse update(@PathVariable UUID employeeId, @Valid @RequestBody UpdateEmployeeFinancialDetailsRequest req, Principal principal) {
        return employeeFinancialDetailsService.update(employeeId, req, principal.getName());
    }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.EmployeeFinancialDetailsResponse;
import com.nforce.onehr.dto.payroll.UpdateEmployeeFinancialDetailsRequest;
import com.nforce.onehr.service.EmployeeFinancialDetailsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** Keka restyle (payroll module) — §3.4 "Import Financial Information" and §3.6 "Financial
 * Information" report. Deliberately gated on payroll:CONFIGURE:ALL for writes, not the Employee
 * module's own employees:EDIT:ALL — this is specifically the Payroll Admin capability Keka places
 * under Payroll Admin → Operations, not a general employee-record edit. */
@RestController
@RequestMapping("/api/payroll/employees")
@RequiredArgsConstructor
public class EmployeeFinancialDetailsController {

    private final EmployeeFinancialDetailsService employeeFinancialDetailsService;

    // Keka restyle — "Financial Information" report (§3.6): every employee in one table.
    @GetMapping("/financial-details")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<EmployeeFinancialDetailsResponse> listAll() {
        return employeeFinancialDetailsService.listAll();
    }

    // US-F03 — "Missing bank details" list for the Super Admin.
    @GetMapping("/missing-bank-details")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<EmployeeFinancialDetailsResponse> missingBank() {
        return employeeFinancialDetailsService.listMissingBank();
    }

    @GetMapping("/{employeeId}/financial-details")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public EmployeeFinancialDetailsResponse get(@PathVariable UUID employeeId) {
        return employeeFinancialDetailsService.get(employeeId);
    }

    @PutMapping("/{employeeId}/financial-details")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public EmployeeFinancialDetailsResponse update(@PathVariable UUID employeeId, @Valid @RequestBody UpdateEmployeeFinancialDetailsRequest req, Principal principal) {
        return employeeFinancialDetailsService.update(employeeId, req, principal.getName());
    }
}

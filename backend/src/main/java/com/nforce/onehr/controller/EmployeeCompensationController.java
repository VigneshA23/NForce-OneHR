package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.AssignCompensationRequest;
import com.nforce.onehr.dto.payroll.EmployeeCompensationResponse;
import com.nforce.onehr.service.EmployeeCompensationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C02 (ONEHR-579), Phase 1. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/employee-compensation")
@RequiredArgsConstructor
public class EmployeeCompensationController {

    private final EmployeeCompensationService employeeCompensationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public EmployeeCompensationResponse assign(@Valid @RequestBody AssignCompensationRequest req, Principal principal) {
        return employeeCompensationService.assign(req, principal.getName());
    }

    @GetMapping("/employee/{employeeId}/current")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public EmployeeCompensationResponse getCurrent(@PathVariable UUID employeeId) {
        return employeeCompensationService.getCurrent(employeeId);
    }

    @GetMapping("/employee/{employeeId}/history")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<EmployeeCompensationResponse> getHistory(@PathVariable UUID employeeId) {
        return employeeCompensationService.getHistory(employeeId);
    }
}

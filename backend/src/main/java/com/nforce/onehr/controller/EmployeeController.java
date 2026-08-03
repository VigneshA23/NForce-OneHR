package com.nforce.onehr.controller;

import com.nforce.onehr.dto.CreateEmployeeRequest;
import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.ManagerDashboardDto;
import com.nforce.onehr.dto.UpdateEmployeeRequest;
import com.nforce.onehr.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<EmployeeResponse> listEmployees() {
        return employeeService.listEmployees();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public EmployeeResponse createEmployee(@Valid @RequestBody CreateEmployeeRequest req, Principal principal) {
        return employeeService.createEmployee(req, principal.getName());
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public EmployeeResponse updateEmployee(@PathVariable UUID userId,
                                           @RequestBody UpdateEmployeeRequest req,
                                           Principal principal) {
        return employeeService.updateEmployee(userId, req, principal.getName());
    }

    @GetMapping("/potential-managers")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<EmployeeResponse> potentialManagers() {
        return employeeService.listPotentialManagers();
    }

    /** Company directory — all authenticated users, work-info only. */
    @GetMapping("/directory")
    public List<DirectoryEntryDto> directory() {
        return employeeService.listDirectory();
    }

    /** Manager dashboard — direct reports for the caller. */
    @GetMapping("/my-reports")
    public ManagerDashboardDto myReports(Principal principal) {
        return employeeService.getManagerDashboard(principal.getName());
    }
}

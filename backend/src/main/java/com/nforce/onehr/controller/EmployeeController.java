package com.nforce.onehr.controller;

import com.nforce.onehr.dto.CreateEmployeeRequest;
import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.EmployeeCodePreviewResponse;
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

    /** Creating new employee accounts is a Super Admin action, done from User Management. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public EmployeeResponse createEmployee(@Valid @RequestBody CreateEmployeeRequest req, Principal principal) {
        return employeeService.createEmployee(req, principal.getName());
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public EmployeeResponse updateEmployee(@PathVariable UUID userId,
                                           @Valid @RequestBody UpdateEmployeeRequest req,
                                           Principal principal) {
        return employeeService.updateEmployee(userId, req, principal.getName());
    }

    @GetMapping("/potential-managers")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<EmployeeResponse> potentialManagers() {
        return employeeService.listPotentialManagers();
    }

    /**
     * Read-only preview of the Employee ID the Add Employee/User form should display — does
     * NOT reserve or consume the sequence. Two admins opening the form at the same time will
     * legitimately see the same preview; the actual ID is only claimed at creation time.
     */
    @GetMapping("/next-code")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public EmployeeCodePreviewResponse nextCode() {
        return new EmployeeCodePreviewResponse(employeeService.previewNextEmployeeCode());
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

    /** HR dashboard — organization-wide equivalent of {@link #myReports}; every user in the org. */
    @GetMapping("/org-dashboard")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ManagerDashboardDto orgDashboard() {
        return employeeService.getOrgDashboard();
    }

    /** Peer directory — colleagues who currently share the caller's manager (My Team: Peers view, ONEHR-73). */
    @GetMapping("/my-peers")
    public List<DirectoryEntryDto> myPeers(Principal principal) {
        return employeeService.listPeers(principal.getName());
    }

    /** The caller's own reporting manager — backs "Appreciate your lead" (ONEHR-73). */
    @GetMapping("/my-manager")
    public EmployeeResponse.ManagerRef myManager(Principal principal) {
        return employeeService.getMyManager(principal.getName());
    }
}

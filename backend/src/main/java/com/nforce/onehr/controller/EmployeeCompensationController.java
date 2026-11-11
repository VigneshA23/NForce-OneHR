package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.AssignCompensationRequest;
import com.nforce.onehr.dto.payroll.EmployeeCompensationResponse;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.EmployeeCompensationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** US-C02 (ONEHR-579), Phase 1. HR Admin/Super Admin only, except the {@code /me/*} endpoints
 * below (US-C02 self-service follow-up) — those have no {@code @PreAuthorize}, same convention as
 * {@code PayslipController}: any authenticated user may call them, and "me" is resolved from the
 * JWT principal only, never a path/query employeeId. */
@RestController
@RequestMapping("/api/payroll/employee-compensation")
@RequiredArgsConstructor
public class EmployeeCompensationController {

    private final EmployeeCompensationService employeeCompensationService;
    private final UserRepository userRepository;

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

    @GetMapping("/me/current")
    public EmployeeCompensationResponse getMyCurrent(Principal principal) {
        return employeeCompensationService.getCurrent(resolveUserId(principal));
    }

    @GetMapping("/me/history")
    public List<EmployeeCompensationResponse> getMyHistory(Principal principal) {
        return employeeCompensationService.getHistory(resolveUserId(principal));
    }

    private UUID resolveUserId(Principal principal) {
        User actor = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new NoSuchElementException("Actor not found"));
        return actor.getId();
    }
}

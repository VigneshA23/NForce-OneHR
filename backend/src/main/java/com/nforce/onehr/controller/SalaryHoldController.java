package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.CreateSalaryHoldRequest;
import com.nforce.onehr.dto.payroll.SalaryHoldResponse;
import com.nforce.onehr.service.SalaryHoldService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C26 (ONEHR-579) — both "Hold processing" and "Hold payout", see SalaryHold's own Javadoc. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/runs/{runId}/salary-holds")
@RequiredArgsConstructor
public class SalaryHoldController {

    private final SalaryHoldService salaryHoldService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public SalaryHoldResponse create(@PathVariable UUID runId, @Valid @RequestBody CreateSalaryHoldRequest req, Principal principal) {
        return salaryHoldService.create(runId, req, principal.getName());
    }

    @PostMapping("/{holdId}/release")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public SalaryHoldResponse release(@PathVariable UUID runId, @PathVariable UUID holdId, Principal principal) {
        return salaryHoldService.release(runId, holdId, principal.getName());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<SalaryHoldResponse> list(@PathVariable UUID runId) {
        return salaryHoldService.list(runId);
    }
}

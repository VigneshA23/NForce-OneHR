package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.PayrollProrationResponse;
import com.nforce.onehr.dto.payroll.RecomputeProrationRequest;
import com.nforce.onehr.service.PayrollProrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;

/** US-C07 (ONEHR-613), Phase 2. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/proration")
@RequiredArgsConstructor
public class PayrollProrationController {

    private final PayrollProrationService payrollProrationService;

    @PostMapping("/recompute")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayrollProrationResponse recompute(@Valid @RequestBody RecomputeProrationRequest req, Principal principal) {
        return payrollProrationService.recompute(req, principal.getName());
    }

    @GetMapping("/{employeeId}")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayrollProrationResponse get(@PathVariable UUID employeeId, @RequestParam UUID payGroupId,
                                         @RequestParam(required = false) LocalDate referenceDate) {
        return payrollProrationService.get(employeeId, payGroupId, referenceDate);
    }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.OverrideLopRequest;
import com.nforce.onehr.dto.payroll.PayrollLopCalculationResponse;
import com.nforce.onehr.dto.payroll.RecomputeLopRequest;
import com.nforce.onehr.service.PayrollLopCalculationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;

/** US-C04 (ONEHR-610) / US-C05 (ONEHR-611), Phase 2. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/lop")
@RequiredArgsConstructor
public class PayrollLopCalculationController {

    private final PayrollLopCalculationService payrollLopCalculationService;

    @PostMapping("/recompute")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayrollLopCalculationResponse recompute(@Valid @RequestBody RecomputeLopRequest req, Principal principal) {
        return payrollLopCalculationService.recompute(req, principal.getName());
    }

    @PostMapping("/override")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayrollLopCalculationResponse override(@Valid @RequestBody OverrideLopRequest req, Principal principal) {
        return payrollLopCalculationService.override(req, principal.getName());
    }

    @GetMapping("/{employeeId}")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayrollLopCalculationResponse get(@PathVariable UUID employeeId, @RequestParam UUID payGroupId,
                                              @RequestParam(required = false) LocalDate referenceDate) {
        return payrollLopCalculationService.get(employeeId, payGroupId, referenceDate);
    }
}

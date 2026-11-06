package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.AdHocDeductionInstallmentResponse;
import com.nforce.onehr.dto.payroll.AdHocDeductionResponse;
import com.nforce.onehr.dto.payroll.CreateAdHocDeductionRequest;
import com.nforce.onehr.service.AdHocDeductionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C25 (ONEHR-579). HR Admin/Super Admin only, same authorities as the rest of this epic. */
@RestController
@RequestMapping("/api/payroll/ad-hoc-deductions")
@RequiredArgsConstructor
public class AdHocDeductionController {

    private final AdHocDeductionService adHocDeductionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public AdHocDeductionResponse create(@Valid @RequestBody CreateAdHocDeductionRequest req, Principal principal) {
        return adHocDeductionService.create(req, principal.getName());
    }

    @PostMapping("/{deductionId}/cancel")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public AdHocDeductionResponse cancel(@PathVariable UUID deductionId, Principal principal) {
        return adHocDeductionService.cancel(deductionId, principal.getName());
    }

    @GetMapping("/{deductionId}")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public AdHocDeductionResponse get(@PathVariable UUID deductionId) {
        return adHocDeductionService.get(deductionId);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<AdHocDeductionResponse> listByEmployee(@RequestParam UUID employeeId) {
        return adHocDeductionService.listByEmployee(employeeId);
    }

    @GetMapping("/{deductionId}/installments")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<AdHocDeductionInstallmentResponse> listInstallments(@PathVariable UUID deductionId) {
        return adHocDeductionService.listInstallments(deductionId);
    }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.CreateStatutoryOverrideRequest;
import com.nforce.onehr.dto.payroll.StatutoryOverrideResponse;
import com.nforce.onehr.dto.payroll.StatutoryPreviewResponse;
import com.nforce.onehr.service.StatutoryOverrideService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Keka's "Statutory Override" step. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/statutory-overrides")
@RequiredArgsConstructor
public class StatutoryOverrideController {

    private final StatutoryOverrideService statutoryOverrideService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public StatutoryOverrideResponse createOrOverride(@Valid @RequestBody CreateStatutoryOverrideRequest req, Principal principal) {
        return statutoryOverrideService.createOrOverride(req, principal.getName());
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public void clear(@RequestParam UUID employeeId, @RequestParam String ruleType, @RequestParam UUID payGroupId,
                       @RequestParam(required = false) LocalDate referenceDate, Principal principal) {
        statutoryOverrideService.clear(employeeId, ruleType, payGroupId, referenceDate, principal.getName());
    }

    @GetMapping("/by-employee/{employeeId}")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<StatutoryOverrideResponse> listForEmployee(@PathVariable UUID employeeId) {
        return statutoryOverrideService.listForEmployee(employeeId);
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public StatutoryPreviewResponse previewComputedAmount(@RequestParam UUID employeeId, @RequestParam String ruleType,
                                                           @RequestParam UUID payGroupId, @RequestParam(required = false) LocalDate referenceDate) {
        return statutoryOverrideService.previewComputedAmount(employeeId, ruleType, payGroupId, referenceDate);
    }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.PayslipSettingsResponse;
import com.nforce.onehr.dto.payroll.UpdatePayslipSettingsRequest;
import com.nforce.onehr.service.PayslipSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Keka restyle (payroll module) — §3.9 "Configure Payslip Settings". Same payroll:VIEW:ALL /
 * payroll:CONFIGURE:ALL split as the rest of Payroll Setup. */
@RestController
@RequestMapping("/api/payroll/pay-groups/{payGroupId}/payslip-settings")
@RequiredArgsConstructor
public class PayslipSettingsController {

    private final PayslipSettingsService payslipSettingsService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayslipSettingsResponse get(@PathVariable UUID payGroupId) {
        return payslipSettingsService.get(payGroupId);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayslipSettingsResponse update(@PathVariable UUID payGroupId, @Valid @RequestBody UpdatePayslipSettingsRequest req) {
        return payslipSettingsService.update(payGroupId, req);
    }
}

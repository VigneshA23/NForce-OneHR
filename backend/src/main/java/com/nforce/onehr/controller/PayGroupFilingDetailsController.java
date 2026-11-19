package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.PayGroupFilingDetailsResponse;
import com.nforce.onehr.dto.payroll.UpdatePayGroupFilingDetailsRequest;
import com.nforce.onehr.service.PayGroupFilingDetailsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Keka restyle (payroll module) — §3.8 "Filing Details" (Income Tax/PF/ESI). Same
 * payroll:VIEW:ALL / payroll:CONFIGURE:ALL split as the rest of Payroll Setup. */
@RestController
@RequestMapping("/api/payroll/pay-groups/{payGroupId}/filing-details")
@RequiredArgsConstructor
public class PayGroupFilingDetailsController {

    private final PayGroupFilingDetailsService payGroupFilingDetailsService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayGroupFilingDetailsResponse get(@PathVariable UUID payGroupId) {
        return payGroupFilingDetailsService.get(payGroupId);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayGroupFilingDetailsResponse update(@PathVariable UUID payGroupId, @Valid @RequestBody UpdatePayGroupFilingDetailsRequest req) {
        return payGroupFilingDetailsService.update(payGroupId, req);
    }
}

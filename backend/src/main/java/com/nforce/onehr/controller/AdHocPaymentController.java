package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.AddAdHocPaymentRequest;
import com.nforce.onehr.dto.payroll.AdHocPaymentResponse;
import com.nforce.onehr.dto.payroll.BulkAdHocPaymentResult;
import com.nforce.onehr.dto.payroll.BulkAddAdHocPaymentRequest;
import com.nforce.onehr.service.AdHocPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C24 (ONEHR-579). HR Admin/Super Admin only, same authorities as the rest of this epic. */
@RestController
@RequestMapping("/api/payroll/runs/{runId}/ad-hoc-payments")
@RequiredArgsConstructor
public class AdHocPaymentController {

    private final AdHocPaymentService adHocPaymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public AdHocPaymentResponse add(@PathVariable UUID runId, @Valid @RequestBody AddAdHocPaymentRequest req, Principal principal) {
        return adHocPaymentService.add(runId, req, principal.getName());
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public BulkAdHocPaymentResult bulkAdd(@PathVariable UUID runId, @Valid @RequestBody BulkAddAdHocPaymentRequest req, Principal principal) {
        return adHocPaymentService.bulkAdd(runId, req, principal.getName());
    }

    @DeleteMapping("/{paymentId}")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public void remove(@PathVariable UUID runId, @PathVariable UUID paymentId, Principal principal) {
        adHocPaymentService.remove(runId, paymentId, principal.getName());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<AdHocPaymentResponse> list(@PathVariable UUID runId) {
        return adHocPaymentService.list(runId);
    }
}

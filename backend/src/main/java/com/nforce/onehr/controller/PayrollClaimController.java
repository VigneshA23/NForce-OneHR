package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.PayrollClaimResponse;
import com.nforce.onehr.service.PayrollClaimService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** "Salary Component Claim"/"Expenses" steps — read-only. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/runs/{runId}/claims")
@RequiredArgsConstructor
public class PayrollClaimController {

    private final PayrollClaimService payrollClaimService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<PayrollClaimResponse> list(@PathVariable UUID runId) {
        return payrollClaimService.listForRun(runId);
    }
}

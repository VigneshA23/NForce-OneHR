package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.PayrollCostReportResponse;
import com.nforce.onehr.service.PayrollCostReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** US-C16 (ONEHR-622), Phase 6. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/runs/{runId}/cost-report")
@RequiredArgsConstructor
public class PayrollCostReportController {

    private final PayrollCostReportService payrollCostReportService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayrollCostReportResponse get(@PathVariable UUID runId) {
        return payrollCostReportService.buildReport(runId);
    }
}

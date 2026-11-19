package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.PayrollJoinersExitsResponse;
import com.nforce.onehr.service.PayrollJoinersExitsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** "New Joinees & Exits" step. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/runs/{runId}/joiners-exits")
@RequiredArgsConstructor
public class PayrollJoinersExitsController {

    private final PayrollJoinersExitsService payrollJoinersExitsService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayrollJoinersExitsResponse get(@PathVariable UUID runId) {
        return payrollJoinersExitsService.build(runId);
    }
}

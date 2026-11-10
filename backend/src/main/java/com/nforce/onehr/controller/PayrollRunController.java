package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.*;
import com.nforce.onehr.service.PayrollRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C08 (ONEHR-614) / US-C10 / US-C11 / US-C27 (whole-run and per-employee rollback), Phase 3. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/runs")
@RequiredArgsConstructor
public class PayrollRunController {

    private final PayrollRunService payrollRunService;

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayrollRunResponse generateDraft(@Valid @RequestBody GenerateDraftRequest req, Principal principal) {
        return payrollRunService.generateDraft(req, principal.getName());
    }

    @PostMapping("/{runId}/submit")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayrollRunResponse submit(@PathVariable UUID runId, Principal principal) {
        return payrollRunService.submit(runId, principal.getName());
    }

    @PostMapping("/{runId}/approve")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayrollRunResponse approve(@PathVariable UUID runId, Principal principal) {
        return payrollRunService.approve(runId, principal.getName());
    }

    @PostMapping("/{runId}/disburse")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayrollRunResponse disburse(@PathVariable UUID runId, Principal principal) {
        return payrollRunService.disburse(runId, principal.getName());
    }

    @PostMapping("/{runId}/rollback")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayrollRunResponse rollback(@PathVariable UUID runId, @Valid @RequestBody RollbackRunRequest req, Principal principal) {
        return payrollRunService.rollbackWholeRun(runId, req, principal.getName());
    }

    @GetMapping("/{runId}")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayrollRunResponse get(@PathVariable UUID runId) {
        return payrollRunService.get(runId);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<PayrollRunResponse> list(@RequestParam UUID payGroupId) {
        return payrollRunService.list(payGroupId);
    }

    @GetMapping("/{runId}/payslips")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<PayslipResponse> listPayslips(@PathVariable UUID runId) {
        return payrollRunService.listPayslips(runId);
    }

    @GetMapping("/{runId}/payslips/{employeeId}")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayslipResponse getPayslip(@PathVariable UUID runId, @PathVariable UUID employeeId) {
        return payrollRunService.getPayslip(runId, employeeId);
    }

    @PostMapping("/{runId}/payslips/{employeeId}/rollback")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayslipResponse rollbackEmployeePayslip(@PathVariable UUID runId, @PathVariable UUID employeeId,
            @Valid @RequestBody RollbackEmployeePayslipRequest req, Principal principal) {
        return payrollRunService.rollbackEmployeePayslip(runId, employeeId, req, principal.getName());
    }
}

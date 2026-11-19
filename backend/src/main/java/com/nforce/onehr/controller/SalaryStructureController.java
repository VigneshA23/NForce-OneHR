package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.CreateSalaryStructureRequest;
import com.nforce.onehr.dto.payroll.SalaryStructureResponse;
import com.nforce.onehr.service.SalaryStructureService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C01 (ONEHR-607) — Payroll Epic (ONEHR-579), Phase 1. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/salary-structures")
@RequiredArgsConstructor
public class SalaryStructureController {

    private final SalaryStructureService salaryStructureService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public SalaryStructureResponse create(@Valid @RequestBody CreateSalaryStructureRequest req, Principal principal) {
        return salaryStructureService.create(req, principal.getName());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<SalaryStructureResponse> list() {
        return salaryStructureService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public SalaryStructureResponse get(@PathVariable UUID id) {
        return salaryStructureService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public SalaryStructureResponse update(@PathVariable UUID id, @Valid @RequestBody CreateSalaryStructureRequest req, Principal principal) {
        return salaryStructureService.update(id, req, principal.getName());
    }
}

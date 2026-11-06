package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.CreateSalaryComponentCatalogRequest;
import com.nforce.onehr.dto.payroll.SalaryComponentCatalogResponse;
import com.nforce.onehr.dto.payroll.UpdateSalaryComponentCatalogRequest;
import com.nforce.onehr.service.SalaryComponentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C20 — the shared Salary Component catalog. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/salary-components")
@RequiredArgsConstructor
public class SalaryComponentController {

    private final SalaryComponentService salaryComponentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public SalaryComponentCatalogResponse create(@Valid @RequestBody CreateSalaryComponentCatalogRequest req, Principal principal) {
        return salaryComponentService.create(req, principal.getName());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public SalaryComponentCatalogResponse update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateSalaryComponentCatalogRequest req,
                                                  Principal principal) {
        return salaryComponentService.update(id, req, principal.getName());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<SalaryComponentCatalogResponse> list() {
        return salaryComponentService.list();
    }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.InvestmentDeclarationResponse;
import com.nforce.onehr.dto.payroll.UpsertInvestmentDeclarationRequest;
import com.nforce.onehr.service.InvestmentDeclarationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Keka restyle (payroll module) — §3.4 "Import Investment Declaration". Same payroll:VIEW:ALL /
 * payroll:CONFIGURE:ALL split as the rest of this module. */
@RestController
@RequestMapping("/api/payroll/investment-declarations")
@RequiredArgsConstructor
public class InvestmentDeclarationController {

    private final InvestmentDeclarationService investmentDeclarationService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<InvestmentDeclarationResponse> list(@RequestParam UUID employeeId) {
        return investmentDeclarationService.list(employeeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public InvestmentDeclarationResponse upsert(@Valid @RequestBody UpsertInvestmentDeclarationRequest req) {
        return investmentDeclarationService.upsert(req);
    }
}

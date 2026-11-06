package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.CreateStatutoryRuleRequest;
import com.nforce.onehr.dto.payroll.StatutoryRuleResponse;
import com.nforce.onehr.service.StatutoryRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/** US-C13 (ONEHR-619), Phase 2 — config/versioning only. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/statutory-rules")
@RequiredArgsConstructor
public class StatutoryRuleController {

    private final StatutoryRuleService statutoryRuleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public StatutoryRuleResponse createOrRevise(@Valid @RequestBody CreateStatutoryRuleRequest req, Principal principal) {
        return statutoryRuleService.createOrRevise(req, principal.getName());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<StatutoryRuleResponse> listCurrent() {
        return statutoryRuleService.listCurrent();
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<StatutoryRuleResponse> history(@RequestParam String country, @RequestParam String ruleType) {
        return statutoryRuleService.history(country, ruleType);
    }
}

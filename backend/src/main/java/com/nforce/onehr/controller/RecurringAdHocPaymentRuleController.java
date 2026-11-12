package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.CreateRecurringAdHocPaymentRuleRequest;
import com.nforce.onehr.dto.payroll.RecurringAdHocPaymentRuleResponse;
import com.nforce.onehr.dto.payroll.SetRecurringAdHocPaymentRuleActiveRequest;
import com.nforce.onehr.service.RecurringAdHocPaymentRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C24 follow-up (ONEHR-579). HR Admin/Super Admin only, same authority as the rest of Payroll
 *  Setup's configuration endpoints. */
@RestController
@RequestMapping("/api/payroll/recurring-ad-hoc-payment-rules")
@RequiredArgsConstructor
public class RecurringAdHocPaymentRuleController {

    private final RecurringAdHocPaymentRuleService recurringAdHocPaymentRuleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public RecurringAdHocPaymentRuleResponse create(@Valid @RequestBody CreateRecurringAdHocPaymentRuleRequest req, Principal principal) {
        return recurringAdHocPaymentRuleService.create(req, principal.getName());
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public RecurringAdHocPaymentRuleResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetRecurringAdHocPaymentRuleActiveRequest req, Principal principal) {
        return recurringAdHocPaymentRuleService.setActive(id, req.isActive(), principal.getName());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public List<RecurringAdHocPaymentRuleResponse> list() {
        return recurringAdHocPaymentRuleService.list();
    }
}

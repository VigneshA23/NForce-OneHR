package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.EmployeeCompensationResponse;
import com.nforce.onehr.dto.payroll.MyCtcBreakdownResponse;
import com.nforce.onehr.service.MyFinancesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * US-C33 (ONEHR-579) slice — "My Finances". No {@code @PreAuthorize}: every authenticated user
 * may see their OWN finances (same convention as {@code ExpenseController#myClaims}) — the
 * boundary is "only ever my own employeeId," enforced in {@code MyFinancesService} by resolving
 * it from the Principal, never accepted as a path or query parameter.
 */
@RestController
@RequestMapping("/api/me/finances")
@RequiredArgsConstructor
public class MyFinancesController {

    private final MyFinancesService myFinancesService;

    @GetMapping("/ctc-breakdown")
    public MyCtcBreakdownResponse ctcBreakdown(Principal principal) {
        return myFinancesService.getCurrentCtcBreakdown(principal.getName());
    }

    @GetMapping("/compensation-history")
    public List<EmployeeCompensationResponse> compensationHistory(Principal principal) {
        return myFinancesService.getCompensationHistory(principal.getName());
    }
}

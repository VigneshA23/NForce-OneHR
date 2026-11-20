package com.nforce.onehr.controller;

import com.nforce.onehr.service.IncomeTaxService;
import com.nforce.onehr.service.MySalaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** US-F07 / US-F09 — My Pay. Self-service only: the user comes from the token, no endpoint takes a user id. */
@RestController
@RequestMapping("/api/me/finances")
@RequiredArgsConstructor
public class MyPayController {

    private final MySalaryService salaryService;
    private final IncomeTaxService incomeTaxService;

    @GetMapping("/salary-timeline")
    public MySalaryService.Timeline salaryTimeline(Principal principal) {
        return salaryService.timeline(principal.getName());
    }

    @GetMapping("/income-tax")
    public IncomeTaxService.Computation incomeTax(@RequestParam(required = false) Integer fy, Principal principal) {
        return incomeTaxService.compute(principal.getName(), fy);
    }
}

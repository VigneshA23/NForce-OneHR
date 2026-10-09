package com.nforce.onehr.controller.talent;

import com.nforce.onehr.dto.talent.TalentDashboardSummary;
import com.nforce.onehr.service.talent.TalentDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Org-wide Talent & Skills Dashboard (org.talent.view). */
@RestController
@RequestMapping("/api/org/talent/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class TalentDashboardController {

    private final TalentDashboardService service;

    @GetMapping
    public TalentDashboardSummary summary() {
        return service.summarize();
    }
}

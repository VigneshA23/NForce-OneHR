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
public class TalentDashboardController {

    private final TalentDashboardService service;

    @GetMapping
    @PreAuthorize("hasAuthority('talent_skills:VIEW:ALL')")
    public TalentDashboardSummary summary() {
        return service.summarize();
    }
}

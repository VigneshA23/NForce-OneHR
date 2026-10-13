package com.nforce.onehr.controller;

import com.nforce.onehr.dto.TeamGrowthResponse;
import com.nforce.onehr.dto.TeamGrowthSummary;
import com.nforce.onehr.service.TeamGrowthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
public class TeamGrowthController {

    private final TeamGrowthService service;

    @GetMapping("/growth")
    public List<TeamGrowthSummary> listTeamGrowthSummaries(Authentication auth) {
        return service.listTeamGrowthSummaries(auth.getName());
    }

    @GetMapping("/{employeeUserId}/growth")
    public TeamGrowthResponse getEmployeeGrowth(@PathVariable UUID employeeUserId, Authentication auth) {
        return service.getEmployeeGrowth(auth.getName(), employeeUserId);
    }
}

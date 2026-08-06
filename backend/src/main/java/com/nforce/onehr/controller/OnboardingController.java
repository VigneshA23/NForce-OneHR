package com.nforce.onehr.controller;

import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.onboarding.OnboardingChecklistDetailDto;
import com.nforce.onehr.dto.onboarding.OnboardingChecklistSummaryDto;
import com.nforce.onehr.dto.onboarding.StartOnboardingRequest;
import com.nforce.onehr.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class OnboardingController {

    private final OnboardingService service;

    @GetMapping
    public List<OnboardingChecklistSummaryDto> queue(Principal principal) {
        return service.listQueue(principal.getName());
    }

    @GetMapping("/eligible-employees")
    public List<EmployeeResponse> eligibleEmployees(Principal principal) {
        return service.eligibleEmployees(principal.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OnboardingChecklistDetailDto start(@Valid @RequestBody StartOnboardingRequest req, Principal principal) {
        return service.startOnboarding(req, principal.getName());
    }

    @GetMapping("/{id}")
    public OnboardingChecklistDetailDto detail(@PathVariable UUID id, Principal principal) {
        return service.getDetail(id, principal.getName());
    }

    @PatchMapping("/{id}/items/{itemId}")
    public OnboardingChecklistDetailDto toggleItem(@PathVariable UUID id, @PathVariable UUID itemId, Principal principal) {
        return service.toggleItem(id, itemId, principal.getName());
    }
}

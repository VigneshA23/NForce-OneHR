package com.nforce.onehr.controller;

import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.onboarding.OnboardingChecklistDetailDto;
import com.nforce.onehr.dto.onboarding.OnboardingChecklistSummaryDto;
import com.nforce.onehr.dto.onboarding.OnboardingStatsDto;
import com.nforce.onehr.dto.onboarding.StartOnboardingRequest;
import com.nforce.onehr.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService service;

    // status: IN_PROGRESS (Onboarding Started tab), COMPLETED (Successfully Onboarded tab) or
    // DEACTIVATED (in-progress checklists of since-deactivated employees — history only).
    @GetMapping
    @PreAuthorize("hasAuthority('onboarding:VIEW:ALL')")
    public Page<OnboardingChecklistSummaryDto> queue(
            @RequestParam String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal) {
        return service.searchQueue(principal.getName(), status, search, page, size);
    }

    // Aggregate KPI cards — independent of the queue/eligible-employees tabs' pagination.
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('onboarding:VIEW:ALL')")
    public OnboardingStatsDto stats(Principal principal) {
        return service.stats(principal.getName());
    }

    @GetMapping("/eligible-employees")
    @PreAuthorize("hasAuthority('onboarding:VIEW:ALL')")
    public Page<EmployeeResponse> eligibleEmployees(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal) {
        return service.eligibleEmployeesPaged(principal.getName(), search, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('onboarding:CREATE:ALL')")
    public OnboardingChecklistDetailDto start(@Valid @RequestBody StartOnboardingRequest req, Principal principal) {
        return service.startOnboarding(req, principal.getName());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('onboarding:VIEW:ALL')")
    public OnboardingChecklistDetailDto detail(@PathVariable UUID id, Principal principal) {
        return service.getDetail(id, principal.getName());
    }

    @PatchMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasAuthority('onboarding:EDIT:ALL')")
    public OnboardingChecklistDetailDto toggleItem(@PathVariable UUID id, @PathVariable UUID itemId, Principal principal) {
        return service.toggleItem(id, itemId, principal.getName());
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('onboarding:APPROVE:ALL')")
    public OnboardingChecklistDetailDto complete(@PathVariable UUID id, Principal principal) {
        return service.completeOnboarding(id, principal.getName());
    }
}

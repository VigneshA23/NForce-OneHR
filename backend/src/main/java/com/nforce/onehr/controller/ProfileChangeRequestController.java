package com.nforce.onehr.controller;

import com.nforce.onehr.dto.ProfileChangeRequestResponse;
import com.nforce.onehr.dto.attendance.ApproveRegularizationRequest;
import com.nforce.onehr.dto.attendance.RejectRegularizationRequest;
import com.nforce.onehr.service.ProfileChangeRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Employee bank / government-ID changes waiting for HR approval. Employees submit through
 * {@code PATCH /api/profile} (ProfileService queues whatever actually changed); this controller
 * lets them see their own requests and lets {@code employee_sensitive:APPROVE:ALL} holders decide.
 */
@RestController
@RequestMapping("/api/profile/change-requests")
@RequiredArgsConstructor
public class ProfileChangeRequestController {

    private final ProfileChangeRequestService service;

    @GetMapping("/mine")
    public List<ProfileChangeRequestResponse> mine(Principal principal) {
        return service.listMine(principal.getName());
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('employee_sensitive:APPROVE:ALL')")
    public List<ProfileChangeRequestResponse> pending(Principal principal) {
        return service.listPending(principal.getName());
    }

    /** Raw values; audit-logged as an unmasked view of sensitive data. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('employee_sensitive:APPROVE:ALL')")
    public ProfileChangeRequestResponse detail(@PathVariable UUID id, Principal principal) {
        return service.detail(id, principal.getName());
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('employee_sensitive:APPROVE:ALL')")
    public ProfileChangeRequestResponse approve(@PathVariable UUID id,
                                                @RequestBody(required = false) ApproveRegularizationRequest req,
                                                Principal principal) {
        return service.approve(id, req != null ? req.getComment() : null, principal.getName());
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('employee_sensitive:APPROVE:ALL')")
    public ProfileChangeRequestResponse reject(@PathVariable UUID id,
                                               @Valid @RequestBody RejectRegularizationRequest req,
                                               Principal principal) {
        return service.reject(id, req.getComment(), principal.getName());
    }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.penalization.ClonePenalisationPolicyRequest;
import com.nforce.onehr.dto.penalization.CreatePenalisationPolicyRequest;
import com.nforce.onehr.dto.penalization.PenalisationPolicySummaryDto;
import com.nforce.onehr.dto.penalization.RenamePenalisationPolicyRequest;
import com.nforce.onehr.service.PenalisationPolicyManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Organization Masters → Penalization Policy → Policy List (Section 5). Distinct path from
 * {@code /api/penalization-policy} (the rule-config document, see {@link PenalizationPolicyController})
 * — this controller manages the named/assignable policy records themselves.
 */
@RestController
@RequestMapping("/api/org/penalisation-policies")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class PenalisationPolicyManagementController {

    private final PenalisationPolicyManagementService service;

    @GetMapping
    public List<PenalisationPolicySummaryDto> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PenalisationPolicySummaryDto create(@Valid @RequestBody CreatePenalisationPolicyRequest request, Principal principal) {
        return service.create(request, principal.getName());
    }

    @PatchMapping("/{id}")
    public PenalisationPolicySummaryDto rename(@PathVariable UUID id, @Valid @RequestBody RenamePenalisationPolicyRequest request,
                                                Principal principal) {
        return service.rename(id, request, principal.getName());
    }

    @PatchMapping("/{id}/toggle-active")
    public PenalisationPolicySummaryDto toggleActive(@PathVariable UUID id, Principal principal) {
        return service.toggleActive(id, principal.getName());
    }

    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public PenalisationPolicySummaryDto clone(@PathVariable UUID id, @Valid @RequestBody ClonePenalisationPolicyRequest request,
                                               Principal principal) {
        return service.clone(id, request, principal.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Principal principal) {
        service.delete(id, principal.getName());
    }
}

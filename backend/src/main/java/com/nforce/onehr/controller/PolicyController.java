package com.nforce.onehr.controller;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.service.PolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService service;

    // ── All authenticated users ────────────────────────────

    @GetMapping("/my")
    public List<PolicyResponse> myPolicies(Principal principal) {
        return service.myPolicies(principal.getName());
    }

    @GetMapping("/my/pending-count")
    public long pendingCount(Principal principal) {
        return service.countPendingRequiredForEmployee(principal.getName());
    }

    @PostMapping("/{id}/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(Principal principal, @PathVariable Long id) {
        service.acknowledge(principal.getName(), id);
    }

    // ── HR/SA ──────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<PolicyResponse> listAll(Principal principal) {
        return service.listAll(principal.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse publish(Principal principal, @Valid @RequestBody PublishPolicyRequest req) {
        return service.publish(principal.getName(), req);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse edit(Principal principal, @PathVariable Long id,
                               @Valid @RequestBody UpdatePolicyRequest req) {
        return service.editPolicy(principal.getName(), id, req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse deactivate(Principal principal, @PathVariable Long id) {
        return service.deactivatePolicy(principal.getName(), id);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse reactivate(Principal principal, @PathVariable Long id) {
        return service.reactivatePolicy(principal.getName(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void delete(Principal principal, @PathVariable Long id) {
        service.deletePolicy(principal.getName(), id);
    }

    @GetMapping("/{id}/acknowledgments")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<PolicyAcknowledgmentResponse> acknowledgments(Principal principal, @PathVariable Long id) {
        return service.listAcknowledgments(principal.getName(), id);
    }

    @DeleteMapping("/{policyId}/acknowledgments/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void resetAcknowledgment(Principal principal, @PathVariable Long policyId, @PathVariable UUID userId) {
        service.resetAcknowledgment(principal.getName(), policyId, userId);
    }

    @PostMapping("/{policyId}/remind/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void remind(Principal principal, @PathVariable Long policyId, @PathVariable UUID userId) {
        service.remindEmployee(principal.getName(), policyId, userId);
    }

    @GetMapping("/pending-ack-count")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public long globalPendingAckCount() {
        return service.countAllPendingRequired();
    }
}

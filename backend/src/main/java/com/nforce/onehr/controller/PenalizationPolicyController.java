package com.nforce.onehr.controller;

import com.nforce.onehr.dto.penalization.PenalizationPolicyRequest;
import com.nforce.onehr.dto.penalization.PenalizationPolicyResponse;
import com.nforce.onehr.dto.penalization.PenalizationPolicyVersionSummary;
import com.nforce.onehr.service.PenalizationPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Organization Masters → Penalization Policy. SUPER_ADMIN and HR_ADMIN only — Manager and
 * Employee have no route to this configuration, matching {@code DocumentTypeController}'s
 * authorization convention exactly. Every mutation goes through {@link PenalizationPolicyService},
 * which is the only writer of {@code PenalizationPolicyVersion} rows; this controller creates no
 * {@code AttendancePenalty} directly and contains no policy-evaluation logic.
 */
@RestController
@RequestMapping("/api/penalization-policy")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class PenalizationPolicyController {

    private final PenalizationPolicyService service;

    @GetMapping("/current")
    public PenalizationPolicyResponse current(@RequestParam(required = false) UUID policyId) {
        return service.getCurrent(policyId)
                .orElseThrow(() -> new NoSuchElementException("Penalization Policy is not configured"));
    }

    @GetMapping("/versions")
    public List<PenalizationPolicyVersionSummary> versions(@RequestParam(required = false) UUID policyId) {
        return service.getVersionHistory(policyId);
    }

    @GetMapping("/versions/{id}")
    public PenalizationPolicyResponse version(@PathVariable UUID id) {
        return service.getVersion(id);
    }

    @PutMapping
    public PenalizationPolicyResponse save(@RequestParam(required = false) UUID policyId,
                                            @Valid @RequestBody PenalizationPolicyRequest request, Principal principal) {
        return service.save(policyId, request, principal.getName());
    }
}

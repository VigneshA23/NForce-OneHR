package com.nforce.onehr.controller;

import com.nforce.onehr.dto.attendance.UpdateWfhPartialLeavePolicyRequest;
import com.nforce.onehr.dto.attendance.WfhPartialLeavePolicyResponse;
import com.nforce.onehr.service.WfhPartialLeavePolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * Super Admin-only configuration for the org-wide WFH monthly-days limit and Partial Day
 * monthly-minutes limit — see WfhPartialLeavePolicyService. AttendanceRequestService reads the
 * same underlying row directly (not through this controller) for every submit/balance check, so
 * a save here takes effect immediately, with no redeploy.
 */
@RestController
@RequestMapping("/api/settings/wfh-partial-leave-policy")
@RequiredArgsConstructor
public class WfhPartialLeavePolicyController {

    private final WfhPartialLeavePolicyService policyService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public WfhPartialLeavePolicyResponse get() {
        return policyService.getPolicy();
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public WfhPartialLeavePolicyResponse update(@Valid @RequestBody UpdateWfhPartialLeavePolicyRequest req, Principal principal) {
        return policyService.updatePolicy(req, principal.getName());
    }
}

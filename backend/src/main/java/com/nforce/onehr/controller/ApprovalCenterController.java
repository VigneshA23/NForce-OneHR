package com.nforce.onehr.controller;

import com.nforce.onehr.dto.ApprovalItemDto;
import com.nforce.onehr.service.ApprovalCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * Unified Approval Center queue.
 *
 * HARD RULE: This is the ONLY endpoint family that issues approval/rejection
 * decisions. No other controller/endpoint may approve or reject any request type.
 * The individual service-level approve/reject endpoints (leave, expenses, assets,
 * regularization) do the actual work; this controller aggregates the pending
 * queue into a single unified view and delegates decisions to the appropriate
 * service.
 */
@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalCenterController {

    private final ApprovalCenterService approvalCenterService;

    /** All pending approval items visible to the caller - see {@link ApprovalCenterService#pendingApprovals}. */
    @GetMapping
    public List<ApprovalItemDto> pendingApprovals(Principal principal) {
        return approvalCenterService.pendingApprovals(principal.getName());
    }
}

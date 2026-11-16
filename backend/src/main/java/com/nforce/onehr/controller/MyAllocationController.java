package com.nforce.onehr.controller;

import com.nforce.onehr.dto.allocation.MyAllocationResponse;
import com.nforce.onehr.service.MyAllocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * US-B08 — an employee's read-only view of their own current and upcoming allocations. No
 * {@code @PreAuthorize}: every authenticated employee may see their OWN allocations (same convention
 * as {@code MyFinancesController}), and the boundary is enforced in {@link MyAllocationService} by
 * resolving the employee from the Principal — never from a path or query parameter. Unauthenticated
 * callers are rejected by the security filter chain.
 *
 * <p>This controller has exactly one handler, a GET. Self-service allocation management is
 * intentionally impossible: nothing here creates, edits or deletes an allocation.
 */
@RestController
@RequestMapping("/api/allocations/me")
@RequiredArgsConstructor
public class MyAllocationController {

    private final MyAllocationService myAllocationService;

    @GetMapping
    public List<MyAllocationResponse> myAllocations(Principal principal) {
        return myAllocationService.myCurrentAndUpcoming(principal.getName());
    }
}

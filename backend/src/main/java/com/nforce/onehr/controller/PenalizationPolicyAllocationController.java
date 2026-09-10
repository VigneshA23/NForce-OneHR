package com.nforce.onehr.controller;

import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.dto.penalization.AllocationDto;
import com.nforce.onehr.dto.penalization.BulkAllocationRequest;
import com.nforce.onehr.dto.penalization.BulkRemoveAllocationRequest;
import com.nforce.onehr.dto.penalization.CheckConflictsRequest;
import com.nforce.onehr.dto.penalization.CreateAllocationRequest;
import com.nforce.onehr.dto.penalization.EmployeeAllocationDetailResponse;
import com.nforce.onehr.dto.penalization.EmployeeAllocationSearchResponse;
import com.nforce.onehr.dto.penalization.PolicyResolutionDetailResponse;
import com.nforce.onehr.dto.penalization.UpdateAllocationRequest;
import com.nforce.onehr.service.PenalizationPolicyAllocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Administration → Organization Masters → Penalization Policy → Penalization Policy Allocation.
 * Distinct from {@code /api/org/penalisation-policies} (the named-policy CRUD, see
 * {@link PenalisationPolicyManagementController}) — this controller assigns employees to those
 * policies with an effective date range.
 */
@RestController
@RequestMapping("/api/org/penalisation-policy-allocations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class PenalizationPolicyAllocationController {

    private final PenalizationPolicyAllocationService service;

    @GetMapping("/employees")
    public EmployeeAllocationSearchResponse searchEmployees(
            @RequestParam(required = false) UUID businessUnitId,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID penalisationPolicyId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            // The main Allocation table shows every matching employee at once (no pagination) —
            // the Add Employees modal keeps using page/size as before, so this defaults to false
            // and leaves that call path untouched.
            @RequestParam(defaultValue = "false") boolean all) {
        return service.searchEmployees(businessUnitId, departmentId, locationId, penalisationPolicyId, search, page, size, all);
    }

    @GetMapping("/employees/{employeeUserId}")
    public EmployeeAllocationDetailResponse getEmployeeDetail(@PathVariable UUID employeeUserId) {
        return service.getEmployeeDetail(employeeUserId);
    }

    /**
     * Section 21: "which policy applies to employee X on date Y" — for any date, not just today.
     * Uses the exact same resolution logic {@link #getEmployeeDetail} (today only) and the
     * attendance engine both already use.
     */
    @GetMapping("/employees/{employeeUserId}/resolve")
    public PolicyResolutionDetailResponse resolvePolicyFor(
            @PathVariable UUID employeeUserId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.resolveFor(employeeUserId, date);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AllocationDto allocate(@Valid @RequestBody CreateAllocationRequest request, Principal principal) {
        return service.allocate(request, principal.getName());
    }

    @PutMapping("/{id}")
    public AllocationDto update(@PathVariable UUID id, @Valid @RequestBody UpdateAllocationRequest request, Principal principal) {
        return service.update(id, request, principal.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id, Principal principal) {
        service.remove(id, principal.getName());
    }

    @PostMapping("/bulk")
    public AssignmentBulkResultResponse bulkAllocate(@Valid @RequestBody BulkAllocationRequest request, Principal principal) {
        return service.bulkAllocate(request, principal.getName());
    }

    @PostMapping("/bulk-remove")
    public AssignmentBulkResultResponse bulkRemove(@Valid @RequestBody BulkRemoveAllocationRequest request, Principal principal) {
        return service.bulkRemove(request, principal.getName());
    }

    @PostMapping("/check-conflicts")
    public Map<UUID, AllocationDto> checkConflicts(@Valid @RequestBody CheckConflictsRequest request) {
        return service.checkConflicts(request.getEmployeeUserIds(), request.getEffectiveFrom(), request.getEffectiveTo(),
                request.getExcludeAllocationId());
    }
}

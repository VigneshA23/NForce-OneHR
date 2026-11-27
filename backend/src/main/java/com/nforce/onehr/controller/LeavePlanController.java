package com.nforce.onehr.controller;

import com.nforce.onehr.dto.LeavePlanDtos.AllocateRequest;
import com.nforce.onehr.dto.LeavePlanDtos.AllocationDto;
import com.nforce.onehr.dto.LeavePlanDtos.BulkAllocateRequest;
import com.nforce.onehr.dto.LeavePlanDtos.BulkRemoveRequest;
import com.nforce.onehr.dto.LeavePlanDtos.ClonePlanRequest;
import com.nforce.onehr.dto.LeavePlanDtos.ConflictCheckRequest;
import com.nforce.onehr.dto.LeavePlanDtos.CreatePlanRequest;
import com.nforce.onehr.dto.LeavePlanDtos.EmployeePlanDetail;
import com.nforce.onehr.dto.LeavePlanDtos.EmployeePlanRow;
import com.nforce.onehr.dto.LeavePlanDtos.PlanDetail;
import com.nforce.onehr.dto.LeavePlanDtos.PlanSummary;
import com.nforce.onehr.dto.LeavePlanDtos.SaveVersionRequest;
import com.nforce.onehr.dto.LeavePlanDtos.UpdateAllocationRequest;
import com.nforce.onehr.dto.LeavePlanDtos.UpdatePlanRequest;
import com.nforce.onehr.dto.LeavePlanDtos.VersionDetail;
import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.service.LeavePlanAllocationService;
import com.nforce.onehr.service.LeavePlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LV-01 Leave Plans — Organization Masters > Leave. VIEW reads plans and who is on them; CONFIGURE
 * changes plans, their rules and assignments. Assignment by rule uses {@code /api/assignment-rules}
 * with domain LEAVE_PLAN (guarded by the same {@code leave_configuration} permission).
 */
@RestController
@RequestMapping("/api/leave-config")
@RequiredArgsConstructor
public class LeavePlanController {

    private final LeavePlanService planService;
    private final LeavePlanAllocationService allocationService;

    // ── Plans ──

    @GetMapping("/plans")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public List<PlanSummary> plans() {
        return planService.list();
    }

    @GetMapping("/plans/{id}")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public PlanDetail plan(@PathVariable UUID id) {
        return planService.detail(id);
    }

    @GetMapping("/plans/{id}/current-version")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public VersionDetail currentVersion(@PathVariable UUID id) {
        return planService.currentVersion(id);
    }

    @GetMapping("/plan-versions/{versionId}")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public VersionDetail version(@PathVariable UUID versionId) {
        return planService.version(versionId);
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public PlanDetail create(@Valid @RequestBody CreatePlanRequest req, Principal principal) {
        return planService.create(req, principal.getName());
    }

    @PatchMapping("/plans/{id}")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public PlanDetail update(@PathVariable UUID id, @Valid @RequestBody UpdatePlanRequest req, Principal principal) {
        return planService.update(id, req, principal.getName());
    }

    /** New rules from a date (a new version, or replaces the version starting that day). */
    @PostMapping("/plans/{id}/versions")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public PlanDetail saveVersion(@PathVariable UUID id, @Valid @RequestBody SaveVersionRequest req, Principal principal) {
        return planService.saveVersion(id, req, principal.getName());
    }

    @PostMapping("/plans/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public PlanDetail clone(@PathVariable UUID id, @Valid @RequestBody ClonePlanRequest req, Principal principal) {
        return planService.clone(id, req, principal.getName());
    }

    @PatchMapping("/plans/{id}/active")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public PlanDetail setActive(@PathVariable UUID id, @RequestBody Map<String, Boolean> body, Principal principal) {
        Boolean active = body.get("active");
        if (active == null) throw new IllegalArgumentException("active is required");
        return planService.setActive(id, active, principal.getName());
    }

    @PatchMapping("/plans/{id}/default")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public PlanDetail setDefault(@PathVariable UUID id, Principal principal) {
        return planService.setDefault(id, principal.getName());
    }

    @DeleteMapping("/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public void delete(@PathVariable UUID id, Principal principal) {
        planService.delete(id, principal.getName());
    }

    // ── Allocation ──

    @GetMapping("/plan-allocations/employees")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public List<EmployeePlanRow> employees() {
        return allocationService.employees();
    }

    @GetMapping("/plan-allocations/employees/{userId}")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public EmployeePlanDetail employee(@PathVariable UUID userId) {
        return allocationService.employee(userId);
    }

    @PostMapping("/plan-allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public AllocationDto allocate(@Valid @RequestBody AllocateRequest req, Principal principal) {
        return allocationService.allocate(req, principal.getName());
    }

    @PutMapping("/plan-allocations/{id}")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public AllocationDto updateAllocation(@PathVariable UUID id, @Valid @RequestBody UpdateAllocationRequest req, Principal principal) {
        return allocationService.update(id, req, principal.getName());
    }

    @DeleteMapping("/plan-allocations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public void removeAllocation(@PathVariable UUID id, Principal principal) {
        allocationService.remove(id, principal.getName());
    }

    @PostMapping("/plan-allocations/bulk")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public AssignmentBulkResultResponse bulkAllocate(@Valid @RequestBody BulkAllocateRequest req, Principal principal) {
        return allocationService.bulkAllocate(req, principal.getName());
    }

    @PostMapping("/plan-allocations/bulk-remove")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public AssignmentBulkResultResponse bulkRemove(@Valid @RequestBody BulkRemoveRequest req, Principal principal) {
        return allocationService.bulkRemove(req, principal.getName());
    }

    @PostMapping("/plan-allocations/check-conflicts")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public Map<UUID, AllocationDto> checkConflicts(@Valid @RequestBody ConflictCheckRequest req) {
        return allocationService.checkConflicts(req);
    }
}

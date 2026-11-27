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
import com.nforce.onehr.service.LeaveExitService;
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
    private final LeaveExitService exitService;
    private final com.nforce.onehr.repository.EmployeeRepository employeeRepository;
    private final com.nforce.onehr.repository.LeaveTypeRepository leaveTypeRepository;

    // ── Exit settlements (LV-04) ──

    /** Leavers' leave positions with a last working day in [from, to] (default: 60 days either side of today). */
    @GetMapping("/exit-settlements")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public List<com.nforce.onehr.dto.LeavePlanDtos.ExitSettlementDto> exitSettlements(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        java.time.LocalDate today = java.time.LocalDate.now();
        return toDtos(exitService.storedBetween(from != null ? from : today.minusDays(60), to != null ? to : today.plusDays(60)));
    }

    /** Recomputes one employee's exit settlement now (after their last working day or leave changed). */
    @PostMapping("/exit-settlements/{userId}/recompute")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public List<com.nforce.onehr.dto.LeavePlanDtos.ExitSettlementDto> recomputeExitSettlement(@PathVariable UUID userId) {
        return toDtos(exitService.settle(userId));
    }

    private List<com.nforce.onehr.dto.LeavePlanDtos.ExitSettlementDto> toDtos(List<com.nforce.onehr.entity.LeaveExitSettlement> rows) {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        rows.forEach(r -> ids.add(r.getEmployeeUserId()));
        java.util.Map<UUID, String> names = new java.util.HashMap<>(), codes = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : employeeRepository.findNamesByUserIds(ids)) names.put((UUID) row[0], (String) row[1]);
            for (Object[] row : employeeRepository.findCodesByUserIds(ids)) codes.put((UUID) row[0], (String) row[1]);
        }
        java.util.Map<UUID, String> typeNames = new java.util.HashMap<>();
        leaveTypeRepository.findAll().forEach(t -> typeNames.put(t.getId(), t.getName()));
        return rows.stream()
                .sorted(java.util.Comparator.comparing(com.nforce.onehr.entity.LeaveExitSettlement::getLastWorkingDay)
                        .thenComparing(r -> names.getOrDefault(r.getEmployeeUserId(), "")))
                .map(r -> new com.nforce.onehr.dto.LeavePlanDtos.ExitSettlementDto(r.getEmployeeUserId(),
                        names.getOrDefault(r.getEmployeeUserId(), "Unknown"), codes.get(r.getEmployeeUserId()), r.getLastWorkingDay(),
                        r.getLeaveTypeId(), typeNames.getOrDefault(r.getLeaveTypeId(), "Leave"), r.getYear(), r.getEntitledDays(),
                        r.getCarriedForwardDays(), r.getUsedDays(), r.getEncashableDays(), r.getRecoveryDays(), r.getComputedAt()))
                .toList();
    }

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

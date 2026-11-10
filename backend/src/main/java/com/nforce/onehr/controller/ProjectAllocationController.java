package com.nforce.onehr.controller;

import com.nforce.onehr.dto.allocation.BulkAllocationResponse;
import com.nforce.onehr.dto.allocation.BulkProjectAllocationRequest;
import com.nforce.onehr.dto.allocation.EmployeeCapacityResponse;
import com.nforce.onehr.dto.allocation.ProjectAllocationRequest;
import com.nforce.onehr.dto.allocation.ProjectAllocationResponse;
import com.nforce.onehr.service.ProjectAllocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/allocations")
@RequiredArgsConstructor
public class ProjectAllocationController {

    private final ProjectAllocationService service;

    /**
     * US-B04: single-employee allocation — the baseline {@link ProjectAllocationService} validates for both this and bulk.
     * Managers may call it, but the service only lets them through for projects they own (US-B03).
     * US-B06: 201 when saved; 200 + {@code warning} (nothing saved) when it would push the employee
     * over 100% — resubmit with confirm=true. Intentionally a soft block, not a 4xx.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ProjectAllocationResponse> allocate(@Valid @RequestBody ProjectAllocationRequest req, Principal principal) {
        ProjectAllocationResponse res = service.allocate(principal.getName(), req);
        return ResponseEntity.status(res.getId() != null ? HttpStatus.CREATED : HttpStatus.OK).body(res);
    }

    /** US-B10: bulk-allocate the same project/capacity/date-range to multiple employees, with per-employee partial success. */
    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public BulkAllocationResponse bulkAllocate(@Valid @RequestBody BulkProjectAllocationRequest req, Principal principal) {
        return service.bulkAllocate(principal.getName(), req);
    }

    /**
     * US-B05/US-B13: a Manager's own team capacity (department/project filters optional, ANDed) —
     * open to Manager too, unlike the two write endpoints above, since this is the read a Manager
     * needs for their own reports.
     */
    @GetMapping("/capacity")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<EmployeeCapacityResponse> capacity(@RequestParam(required = false) UUID departmentId,
                                                     @RequestParam(required = false) UUID projectId,
                                                     Principal principal) {
        return service.capacityView(principal.getName(), departmentId, projectId);
    }
}

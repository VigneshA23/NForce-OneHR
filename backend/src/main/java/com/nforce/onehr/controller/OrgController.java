package com.nforce.onehr.controller;

import com.nforce.onehr.dto.HierarchyNodeDto;
import com.nforce.onehr.dto.org.*;
import com.nforce.onehr.service.OrgService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;

    // ── Hierarchy ────────────────────────────────────────────────────────────

    @GetMapping("/hierarchy")
    public List<HierarchyNodeDto> hierarchy() {
        return orgService.getHierarchy();
    }

    // ── Departments ───────────────────────────────────────────────────────────

    @GetMapping("/departments")
    public List<DepartmentResponse> listDepartments() {
        return orgService.listDepartments();
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentResponse createDepartment(@Valid @RequestBody CreateDepartmentRequest req) {
        return orgService.createDepartment(req);
    }

    @PutMapping("/departments/{id}")
    public DepartmentResponse updateDepartment(@PathVariable UUID id, @Valid @RequestBody UpdateDepartmentRequest req) {
        return orgService.updateDepartment(id, req);
    }

    @PatchMapping("/departments/{id}/toggle-active")
    public DepartmentResponse toggleDepartmentActive(@PathVariable UUID id) {
        return orgService.toggleDepartmentActive(id);
    }

    @DeleteMapping("/departments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDepartment(@PathVariable UUID id) {
        orgService.deleteDepartment(id);
    }

    // ── Designations ──────────────────────────────────────────────────────────

    @GetMapping("/designations")
    public List<DesignationResponse> listDesignations() {
        return orgService.listDesignations();
    }

    @PostMapping("/designations")
    @ResponseStatus(HttpStatus.CREATED)
    public DesignationResponse createDesignation(@Valid @RequestBody CreateDesignationRequest req) {
        return orgService.createDesignation(req);
    }

    @PutMapping("/designations/{id}")
    public DesignationResponse updateDesignation(@PathVariable UUID id, @Valid @RequestBody UpdateDesignationRequest req) {
        return orgService.updateDesignation(id, req);
    }

    @PatchMapping("/designations/{id}/toggle-active")
    public DesignationResponse toggleDesignationActive(@PathVariable UUID id) {
        return orgService.toggleDesignationActive(id);
    }

    @DeleteMapping("/designations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDesignation(@PathVariable UUID id) {
        orgService.deleteDesignation(id);
    }

    // ── Locations ─────────────────────────────────────────────────────────────

    @GetMapping("/locations")
    public List<LocationResponse> listLocations() {
        return orgService.listLocations();
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public LocationResponse createLocation(@Valid @RequestBody CreateLocationRequest req) {
        return orgService.createLocation(req);
    }

    @PutMapping("/locations/{id}")
    public LocationResponse updateLocation(@PathVariable UUID id, @Valid @RequestBody UpdateLocationRequest req) {
        return orgService.updateLocation(id, req);
    }

    @PatchMapping("/locations/{id}/toggle-active")
    public LocationResponse toggleLocationActive(@PathVariable UUID id) {
        return orgService.toggleLocationActive(id);
    }

    @DeleteMapping("/locations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLocation(@PathVariable UUID id) {
        orgService.deleteLocation(id);
    }

    // ── Shifts ────────────────────────────────────────────────────────────────
    // Create/edit/delete are Super Admin only — enforced in OrgService (@PreAuthorize), not
    // just here; listing itself has no role restriction (every employee's own attendance flow
    // reads shift config indirectly via AttendanceService, and My Team / HR views need to list
    // shifts for assignment regardless of role).

    @GetMapping("/shifts")
    public List<ShiftResponse> listShifts() {
        return orgService.listShifts();
    }

    @GetMapping("/shifts/{id}/employees")
    public List<ShiftEmployeeResponse> listShiftEmployees(@PathVariable UUID id) {
        return orgService.listShiftEmployees(id);
    }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftResponse createShift(@Valid @RequestBody CreateShiftRequest req) {
        return orgService.createShift(req);
    }

    @PutMapping("/shifts/{id}")
    public ShiftResponse updateShift(@PathVariable UUID id, @Valid @RequestBody UpdateShiftRequest req) {
        return orgService.updateShift(id, req);
    }

    @PatchMapping("/shifts/{id}/toggle-active")
    public ShiftResponse toggleShiftActive(@PathVariable UUID id) {
        return orgService.toggleShiftActive(id);
    }

    @DeleteMapping("/shifts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteShift(@PathVariable UUID id) {
        orgService.deleteShift(id);
    }
}

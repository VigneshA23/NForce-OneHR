package com.nforce.onehr.controller;

import com.nforce.onehr.dto.org.*;
import com.nforce.onehr.service.OrgService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;

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
}

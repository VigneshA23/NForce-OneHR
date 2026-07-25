package com.nforce.onehr.service;

import com.nforce.onehr.dto.org.*;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Designation;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.DesignationRepository;
import com.nforce.onehr.repository.LocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrgService {

    private final DepartmentRepository departmentRepo;
    private final DesignationRepository designationRepo;
    private final LocationRepository locationRepo;

    // ── Departments ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DepartmentResponse> listDepartments() {
        return departmentRepo.findAll().stream()
                .map(DepartmentResponse::from)
                .toList();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public DepartmentResponse createDepartment(CreateDepartmentRequest req) {
        if (departmentRepo.existsByName(req.getName().trim())) {
            throw new IllegalArgumentException("A department named '" + req.getName().trim() + "' already exists");
        }
        Department saved = departmentRepo.save(
                Department.builder().name(req.getName().trim()).build());
        return DepartmentResponse.from(saved);
    }

    // ── Designations ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DesignationResponse> listDesignations() {
        return designationRepo.findAll().stream()
                .map(DesignationResponse::from)
                .toList();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public DesignationResponse createDesignation(CreateDesignationRequest req) {
        if (designationRepo.existsByTitle(req.getTitle().trim())) {
            throw new IllegalArgumentException("A designation titled '" + req.getTitle().trim() + "' already exists");
        }
        Designation saved = designationRepo.save(
                Designation.builder()
                        .title(req.getTitle().trim())
                        .grade(req.getGrade() != null ? req.getGrade().trim() : null)
                        .build());
        return DesignationResponse.from(saved);
    }

    // ── Locations ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LocationResponse> listLocations() {
        return locationRepo.findAll().stream()
                .map(LocationResponse::from)
                .toList();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public LocationResponse createLocation(CreateLocationRequest req) {
        if (locationRepo.existsByName(req.getName().trim())) {
            throw new IllegalArgumentException("A location named '" + req.getName().trim() + "' already exists");
        }
        Location saved = locationRepo.save(
                Location.builder()
                        .name(req.getName().trim())
                        .city(req.getCity())
                        .state(req.getState())
                        .country(req.getCountry())
                        .build());
        return LocationResponse.from(saved);
    }
}

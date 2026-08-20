package com.nforce.onehr.service;

import com.nforce.onehr.dto.HierarchyNodeDto;
import com.nforce.onehr.dto.org.*;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Designation;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.DesignationRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrgService {

    private final DepartmentRepository departmentRepo;
    private final DesignationRepository designationRepo;
    private final LocationRepository locationRepo;
    private final ShiftRepository shiftRepo;
    private final EmployeeRepository employeeRepo;
    private final EmployeeManagerHistoryRepository historyRepo;

    // ── Departments ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DepartmentResponse> listDepartments() {
        return departmentRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(d -> DepartmentResponse.from(d, employeeRepo.countByDepartmentId(d.getId())))
                .toList();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public DepartmentResponse createDepartment(CreateDepartmentRequest req) {
        if (departmentRepo.existsByNameIgnoreCase(req.getName().trim())) {
            throw new IllegalArgumentException("A department named '" + req.getName().trim() + "' already exists");
        }
        Department saved = departmentRepo.save(
                Department.builder().name(req.getName().trim()).build());
        return DepartmentResponse.from(saved, 0L);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public DepartmentResponse updateDepartment(UUID id, UpdateDepartmentRequest req) {
        Department dept = departmentRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Department not found"));
        String trimmed = req.getName().trim();
        if (!dept.getName().equalsIgnoreCase(trimmed) && departmentRepo.existsByNameIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("A department named '" + trimmed + "' already exists");
        }
        dept.setName(trimmed);
        long count = employeeRepo.countByDepartmentId(id);
        return DepartmentResponse.from(departmentRepo.save(dept), count);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public DepartmentResponse toggleDepartmentActive(UUID id) {
        Department dept = departmentRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Department not found"));
        dept.setActive(!dept.isActive());
        long count = employeeRepo.countByDepartmentId(id);
        return DepartmentResponse.from(departmentRepo.save(dept), count);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public void deleteDepartment(UUID id) {
        Department dept = departmentRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Department not found"));
        long count = employeeRepo.countByDepartmentId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    count + " employee" + (count == 1 ? " is" : "s are") + " assigned to this department. Deactivate instead.");
        }
        departmentRepo.delete(dept);
    }

    // ── Designations ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DesignationResponse> listDesignations() {
        return designationRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(d -> DesignationResponse.from(d, employeeRepo.countByDesignationId(d.getId())))
                .toList();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public DesignationResponse createDesignation(CreateDesignationRequest req) {
        if (designationRepo.existsByTitleIgnoreCase(req.getTitle().trim())) {
            throw new IllegalArgumentException("A designation titled '" + req.getTitle().trim() + "' already exists");
        }
        Designation saved = designationRepo.save(
                Designation.builder()
                        .title(req.getTitle().trim())
                        .grade(req.getGrade() != null ? req.getGrade().trim() : null)
                        .level(req.getLevel() != null ? req.getLevel().trim() : null)
                        .build());
        return DesignationResponse.from(saved, 0L);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public DesignationResponse updateDesignation(UUID id, UpdateDesignationRequest req) {
        Designation desig = designationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Designation not found"));
        String trimmed = req.getTitle().trim();
        if (!desig.getTitle().equalsIgnoreCase(trimmed) && designationRepo.existsByTitleIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("A designation titled '" + trimmed + "' already exists");
        }
        desig.setTitle(trimmed);
        desig.setGrade(req.getGrade() != null ? req.getGrade().trim() : null);
        desig.setLevel(req.getLevel() != null ? req.getLevel().trim() : null);
        long count = employeeRepo.countByDesignationId(id);
        return DesignationResponse.from(designationRepo.save(desig), count);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public DesignationResponse toggleDesignationActive(UUID id) {
        Designation desig = designationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Designation not found"));
        desig.setActive(!desig.isActive());
        long count = employeeRepo.countByDesignationId(id);
        return DesignationResponse.from(designationRepo.save(desig), count);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public void deleteDesignation(UUID id) {
        Designation desig = designationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Designation not found"));
        long count = employeeRepo.countByDesignationId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    count + " employee" + (count == 1 ? " is" : "s are") + " assigned to this designation. Deactivate instead.");
        }
        designationRepo.delete(desig);
    }

    // ── Locations ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LocationResponse> listLocations() {
        return locationRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(l -> LocationResponse.from(l, employeeRepo.countByLocationId(l.getId())))
                .toList();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public LocationResponse createLocation(CreateLocationRequest req) {
        if (locationRepo.existsByNameIgnoreCase(req.getName().trim())) {
            throw new IllegalArgumentException("A location named '" + req.getName().trim() + "' already exists");
        }
        Location saved = locationRepo.save(
                Location.builder()
                        .name(req.getName().trim())
                        .city(req.getCity())
                        .state(req.getState())
                        .country(req.getCountry())
                        .holidayRegion(req.getHolidayRegion())
                        .timezone(validatedTimezone(req.getTimezone()))
                        .build());
        return LocationResponse.from(saved, 0L);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public LocationResponse updateLocation(UUID id, UpdateLocationRequest req) {
        Location loc = locationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Location not found"));
        String trimmed = req.getName().trim();
        if (!loc.getName().equalsIgnoreCase(trimmed) && locationRepo.existsByNameIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("A location named '" + trimmed + "' already exists");
        }
        loc.setName(trimmed);
        loc.setCity(req.getCity() != null ? req.getCity().trim() : null);
        loc.setState(req.getState() != null ? req.getState().trim() : null);
        loc.setCountry(req.getCountry() != null ? req.getCountry().trim() : null);
        loc.setHolidayRegion(req.getHolidayRegion() != null ? req.getHolidayRegion().trim() : null);
        loc.setTimezone(validatedTimezone(req.getTimezone()));
        long count = employeeRepo.countByLocationId(id);
        return LocationResponse.from(locationRepo.save(loc), count);
    }

    // Null/blank clears it (falls back to the global business zone at read time — see
    // AttendanceService.zoneIdFor); any non-blank value must be a real IANA zone id, since a
    // typo here would otherwise only surface later as a confusing ZoneId parse failure deep
    // inside attendance calculations.
    private String validatedTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) return null;
        String trimmed = timezone.trim();
        try {
            java.time.ZoneId.of(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("'" + trimmed + "' is not a valid timezone (use an IANA zone id, e.g. Asia/Kolkata)");
        }
        return trimmed;
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public LocationResponse toggleLocationActive(UUID id) {
        Location loc = locationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Location not found"));
        loc.setActive(!loc.isActive());
        long count = employeeRepo.countByLocationId(id);
        return LocationResponse.from(locationRepo.save(loc), count);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public void deleteLocation(UUID id) {
        Location loc = locationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Location not found"));
        long count = employeeRepo.countByLocationId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    count + " employee" + (count == 1 ? " is" : "s are") + " assigned to this location. Deactivate instead.");
        }
        locationRepo.delete(loc);
    }

    // ── Shifts (master data) ─────────────────────────────────────────────────
    //
    // Deliberately Super Admin ONLY (not SUPER_ADMIN + HR_ADMIN like Departments/Designations/
    // Locations above) — shift definitions are a stricter master-data category per explicit
    // requirement: HR/HR Admin/Manager may ASSIGN an existing shift to an employee (see
    // EmployeeAssignmentController, unaffected by this), but must not be able to create, edit,
    // or delete the shift definition itself.

    @Transactional(readOnly = true)
    public List<ShiftResponse> listShifts() {
        return shiftRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(s -> ShiftResponse.from(s, employeeRepo.countByShiftId(s.getId())))
                .toList();
    }

    /** The employees currently assigned to a shift — backs the Shifts table's Employees drill-down. */
    @Transactional(readOnly = true)
    public List<ShiftEmployeeResponse> listShiftEmployees(UUID shiftId) {
        if (!shiftRepo.existsById(shiftId)) {
            throw new NoSuchElementException("Shift not found");
        }
        return employeeRepo.findByShiftIdWithDetails(shiftId).stream()
                .map(e -> ShiftEmployeeResponse.builder()
                        .userId(e.getUserId())
                        .employeeCode(e.getEmployeeCode())
                        .fullName(e.getFullName())
                        .email(e.getUser().getEmail())
                        .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                        .build())
                .toList();
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ShiftResponse createShift(CreateShiftRequest req) {
        String trimmedName = req.getName().trim();
        if (shiftRepo.existsByNameIgnoreCase(trimmedName)) {
            throw new IllegalArgumentException("A shift named '" + trimmedName + "' already exists");
        }
        String trimmedCode = normalizeCode(req.getCode());
        if (trimmedCode != null && shiftRepo.existsByCodeIgnoreCase(trimmedCode)) {
            throw new IllegalArgumentException("A shift with code '" + trimmedCode + "' already exists");
        }
        if (!req.getEndTime().equals(req.getStartTime()) && req.getBreakMinutes() != null && req.getBreakMinutes() < 0) {
            throw new IllegalArgumentException("Break duration cannot be negative");
        }
        Shift saved = shiftRepo.save(Shift.builder()
                .name(trimmedName)
                .code(trimmedCode)
                .description(blankToNull(req.getDescription()))
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .flexible(req.isFlexible())
                .breakMinutes(req.getBreakMinutes())
                .workingDays(normalizeWorkingDays(req.getWorkingDays()))
                .build());
        return ShiftResponse.from(saved, 0L);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ShiftResponse updateShift(UUID id, UpdateShiftRequest req) {
        Shift shift = shiftRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Shift not found"));
        String trimmedName = req.getName().trim();
        if (!shift.getName().equalsIgnoreCase(trimmedName) && shiftRepo.existsByNameIgnoreCase(trimmedName)) {
            throw new IllegalArgumentException("A shift named '" + trimmedName + "' already exists");
        }
        String trimmedCode = normalizeCode(req.getCode());
        if (trimmedCode != null && !trimmedCode.equalsIgnoreCase(shift.getCode()) && shiftRepo.existsByCodeIgnoreCase(trimmedCode)) {
            throw new IllegalArgumentException("A shift with code '" + trimmedCode + "' already exists");
        }
        if (req.getBreakMinutes() != null && req.getBreakMinutes() < 0) {
            throw new IllegalArgumentException("Break duration cannot be negative");
        }
        shift.setName(trimmedName);
        shift.setCode(trimmedCode);
        shift.setDescription(blankToNull(req.getDescription()));
        shift.setStartTime(req.getStartTime());
        shift.setEndTime(req.getEndTime());
        shift.setFlexible(req.isFlexible());
        shift.setBreakMinutes(req.getBreakMinutes());
        shift.setWorkingDays(normalizeWorkingDays(req.getWorkingDays()));
        long count = employeeRepo.countByShiftId(id);
        return ShiftResponse.from(shiftRepo.save(shift), count);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ShiftResponse toggleShiftActive(UUID id) {
        Shift shift = shiftRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Shift not found"));
        shift.setActive(!shift.isActive());
        long count = employeeRepo.countByShiftId(id);
        return ShiftResponse.from(shiftRepo.save(shift), count);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public void deleteShift(UUID id) {
        Shift shift = shiftRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Shift not found"));
        long count = employeeRepo.countByShiftId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    count + " employee" + (count == 1 ? " is" : "s are") + " assigned to this shift. Deactivate instead.");
        }
        shiftRepo.delete(shift);
    }

    private String normalizeCode(String code) {
        return (code == null || code.isBlank()) ? null : code.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private String normalizeWorkingDays(List<String> days) {
        if (days == null || days.isEmpty()) return null;
        return days.stream()
                .map(String::trim)
                .filter(d -> !d.isBlank())
                .map(d -> DayOfWeek.valueOf(d.toUpperCase())) // throws IllegalArgumentException on an invalid day name
                .map(Enum::name)
                .distinct()
                .collect(Collectors.joining(","));
    }

    // ── Org Hierarchy ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<HierarchyNodeDto> getHierarchy() {
        List<Employee> employees = employeeRepo.findAllWithDetails();
        Map<UUID, String> managerMap = historyRepo.findByEffectiveToIsNull().stream()
                .collect(Collectors.toMap(
                        h -> h.getEmployeeUserId(),
                        h -> h.getManagerUserId().toString(),
                        (a, b) -> a
                ));
        return employees.stream()
                .map(e -> HierarchyNodeDto.builder()
                        .userId(e.getUserId().toString())
                        .fullName(e.getFullName())
                        .designationName(e.getDesignation() != null ? e.getDesignation().getTitle() : null)
                        .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                        .managerId(managerMap.get(e.getUserId()))
                        .active(e.getUser().isActive())
                        .build())
                .collect(Collectors.toList());
    }
}

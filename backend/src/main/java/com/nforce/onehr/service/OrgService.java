package com.nforce.onehr.service;

import com.nforce.onehr.dto.HierarchyNodeDto;
import com.nforce.onehr.dto.org.*;
import com.nforce.onehr.entity.BusinessUnit;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Designation;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.BusinessUnitRepository;
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

    private final BusinessUnitRepository businessUnitRepo;
    private final DepartmentRepository departmentRepo;
    private final DesignationRepository designationRepo;
    private final LocationRepository locationRepo;
    private final ShiftRepository shiftRepo;
    private final EmployeeRepository employeeRepo;
    private final EmployeeManagerHistoryRepository historyRepo;

    // ── Business Units ───────────────────────────────────────────────────────

    /** Same batched-count fix as listDepartments — see that method's own comment. */
    @Transactional(readOnly = true)
    public List<BusinessUnitResponse> listBusinessUnits() {
        Map<UUID, Long> counts = toCountMap(employeeRepo.countGroupedByBusinessUnitId());
        return businessUnitRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(b -> BusinessUnitResponse.from(b, counts.getOrDefault(b.getId(), 0L)))
                .toList();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public BusinessUnitResponse createBusinessUnit(CreateBusinessUnitRequest req) {
        if (businessUnitRepo.existsByNameIgnoreCase(req.getName().trim())) {
            throw new IllegalArgumentException("A business unit named '" + req.getName().trim() + "' already exists");
        }
        BusinessUnit saved = businessUnitRepo.save(
                BusinessUnit.builder().name(req.getName().trim()).build());
        return BusinessUnitResponse.from(saved, 0L);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public BusinessUnitResponse updateBusinessUnit(UUID id, UpdateBusinessUnitRequest req) {
        BusinessUnit unit = businessUnitRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Business unit not found"));
        String trimmed = req.getName().trim();
        if (!unit.getName().equalsIgnoreCase(trimmed) && businessUnitRepo.existsByNameIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("A business unit named '" + trimmed + "' already exists");
        }
        unit.setName(trimmed);
        long count = employeeRepo.countByBusinessUnitId(id);
        return BusinessUnitResponse.from(businessUnitRepo.save(unit), count);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public BusinessUnitResponse toggleBusinessUnitActive(UUID id) {
        BusinessUnit unit = businessUnitRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Business unit not found"));
        unit.setActive(!unit.isActive());
        long count = employeeRepo.countByBusinessUnitId(id);
        return BusinessUnitResponse.from(businessUnitRepo.save(unit), count);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public void deleteBusinessUnit(UUID id) {
        BusinessUnit unit = businessUnitRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Business unit not found"));
        long count = employeeRepo.countByBusinessUnitId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    count + " employee" + (count == 1 ? " is" : "s are") + " assigned to this business unit. Deactivate instead.");
        }
        businessUnitRepo.delete(unit);
    }

    // ── Departments ──────────────────────────────────────────────────────────

    /**
     * One GROUP BY count query for every department, instead of {@code countByDepartmentId}
     * once per row (was N extra round trips for N departments on every Organization Masters
     * page load) — see EmployeeRepository.countGroupedByDepartmentId's own comment.
     */
    @Transactional(readOnly = true)
    public List<DepartmentResponse> listDepartments() {
        Map<UUID, Long> counts = toCountMap(employeeRepo.countGroupedByDepartmentId());
        return departmentRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(d -> DepartmentResponse.from(d, counts.getOrDefault(d.getId(), 0L)))
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

    /** Same batched-count fix as listDepartments — see that method's own comment. */
    @Transactional(readOnly = true)
    public List<DesignationResponse> listDesignations() {
        Map<UUID, Long> counts = toCountMap(employeeRepo.countGroupedByDesignationId());
        return designationRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(d -> DesignationResponse.from(d, counts.getOrDefault(d.getId(), 0L)))
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

    /** Same batched-count fix as listDepartments — see that method's own comment. */
    @Transactional(readOnly = true)
    public List<LocationResponse> listLocations() {
        Map<UUID, Long> counts = toCountMap(employeeRepo.countGroupedByLocationId());
        return locationRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(l -> LocationResponse.from(l, counts.getOrDefault(l.getId(), 0L)))
                .toList();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    @Transactional
    public LocationResponse createLocation(CreateLocationRequest req) {
        String normalized = toTitleCase(req.getName().trim());
        if (locationRepo.existsByNameIgnoreCase(normalized)) {
            throw new IllegalArgumentException("A location named '" + normalized + "' already exists");
        }
        Location saved = locationRepo.save(
                Location.builder()
                        .name(normalized)
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
        String trimmed = toTitleCase(req.getName().trim());
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

    /** Normalizes a location name so each word starts with an uppercase letter, e.g. "new york" -> "New York". */
    private String toTitleCase(String name) {
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                sb.append(" ");
            }
            String word = words[i];
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    // ── Shifts (master data) ─────────────────────────────────────────────────
    //
    // Deliberately Super Admin ONLY (not SUPER_ADMIN + HR_ADMIN like Departments/Designations/
    // Locations above) — shift definitions are a stricter master-data category per explicit
    // requirement: HR/HR Admin/Manager may ASSIGN an existing shift to an employee (see
    // EmployeeAssignmentController, unaffected by this), but must not be able to create, edit,
    // or delete the shift definition itself.

    /**
     * Same batched-count fix as listDepartments (see that method's own comment) — this one also
     * backs the Add/Edit User Shift dropdown's data source (listShifts is reused there), so it
     * was doubly on the critical path for the "dropdowns take too long to load" complaint.
     */
    @Transactional(readOnly = true)
    public List<ShiftResponse> listShifts() {
        Map<UUID, Long> counts = toCountMap(employeeRepo.countGroupedByShiftId());
        return shiftRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(s -> ShiftResponse.from(s, counts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    /** Turns a countGroupedBy*Id() Object[]{id, count} projection into a lookup map. */
    private static Map<UUID, Long> toCountMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
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
                        .active(e.getUser().isActive())
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

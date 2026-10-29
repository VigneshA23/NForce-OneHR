package com.nforce.onehr.service;

import com.nforce.onehr.dto.HierarchyNodeDto;
import com.nforce.onehr.dto.org.*;
import com.nforce.onehr.entity.BusinessUnit;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Designation;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.entity.WeeklyOffPolicyVersion;
import com.nforce.onehr.repository.AssetRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.BusinessUnitRepository;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.DesignationRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.EmployeeShiftAssignmentRepository;
import com.nforce.onehr.repository.EmployeeWeeklyOffPolicyAssignmentRepository;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.repository.ShiftVersionRepository;
import com.nforce.onehr.repository.WeeklyOffPolicyRepository;
import com.nforce.onehr.repository.WeeklyOffPolicyVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrgService {

    // Matches the pre-migration global app.attendance.late-grace-minutes default (V168) — an
    // admin who doesn't set an explicit grace when creating/editing a Shift gets identical
    // behavior to before Shift-level grace existed.
    private static final int DEFAULT_LATE_GRACE_MINUTES = 10;

    private final BusinessUnitRepository businessUnitRepo;
    private final DepartmentRepository departmentRepo;
    private final DesignationRepository designationRepo;
    private final LocationRepository locationRepo;
    private final ShiftRepository shiftRepo;
    private final ShiftVersionRepository shiftVersionRepository;
    private final ShiftVersionResolver shiftVersionResolver;
    // The one existing source of truth for "how long can a Shift be" — see validateShiftDuration's
    // own Javadoc for why this is reused rather than a second, hardcoded 18h limit.
    private final ShiftWeeklyOffRulesService shiftWeeklyOffRulesService;
    private final WeeklyOffPolicyRepository weeklyOffPolicyRepo;
    private final EmployeeRepository employeeRepo;
    private final EmployeeManagerHistoryRepository historyRepo;
    private final HolidayRepository holidayRepo;
    private final AssetRepository assetRepo;
    // Read-only — used exclusively by deleteShift's historical-usage guard below, to block
    // deleting a Shift any Attendance (including historical, already-closed records) still
    // references via its snapshotted shiftId (see V163's migration). Never written to by this
    // class — Shift Version create/update/delete never touches Attendance in any way (see
    // orgService_hasNoAttendanceRepositoryDependencyAtAll's own test, updated alongside this).
    private final AttendanceRepository attendanceRepo;
    // Read-only — used by deleteShift's guards alongside attendanceRepo above, two ways: (1) the
    // same authoritative existsCurrentOrFutureAssignmentForShift check toggleShiftActive's D7 guard
    // already uses, since employeeRepo.countByShiftId below reads Employee.shiftId, a best-effort
    // display cache EmployeeAssignmentService's bulk-reassignment path never updates (see its own
    // Javadoc) — relying on it alone could let a Shift someone was bulk-reassigned onto (or
    // scheduled onto, for the future) be deleted out from under them; (2) existsByShiftId, since
    // employee_shift_assignments.shift_id has no ON DELETE clause of its own (V172), so a Shift with
    // zero current/future assignments can still be referenced by a purely historical/superseded
    // EmployeeShiftAssignment row — this turns that case into the same clear message rather than a
    // raw FK-violation error.
    private final EmployeeShiftAssignmentRepository employeeShiftAssignmentRepo;
    // Phase 3: Weekly Off Policy definition versioning — mirrors shiftVersionRepository's identical
    // role for a Shift's own timing. Used exclusively by createWeeklyOffPolicy/updateWeeklyOffPolicy
    // below; resolution for a business calculation always goes through WeeklyOffPolicyVersionResolver
    // instead (see WorkingDayService).
    private final WeeklyOffPolicyVersionRepository weeklyOffPolicyVersionRepo;
    // Read-only — used exclusively by deleteWeeklyOffPolicy's historical/pending-usage guard,
    // mirroring employeeShiftAssignmentRepo's identical role for deleteShift: this table's own
    // weekly_off_policy_id FK has no ON DELETE clause, so a policy with zero *current* employees
    // can still be referenced by assignment history.
    private final EmployeeWeeklyOffPolicyAssignmentRepository employeeWeeklyOffPolicyAssignmentRepo;

    // ── Business Units ───────────────────────────────────────────────────────

    /** Same batched-count fix as listDepartments — see that method's own comment. */
    @Transactional(readOnly = true)
    public List<BusinessUnitResponse> listBusinessUnits() {
        Map<UUID, Long> counts = toCountMap(employeeRepo.countGroupedByBusinessUnitId());
        return businessUnitRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(b -> BusinessUnitResponse.from(b, counts.getOrDefault(b.getId(), 0L)))
                .toList();
    }

    @PreAuthorize("hasAuthority('organization:CREATE:ALL')")
    @Transactional
    public BusinessUnitResponse createBusinessUnit(CreateBusinessUnitRequest req) {
        if (businessUnitRepo.existsByNameIgnoreCase(req.getName().trim())) {
            throw new IllegalArgumentException("A business unit named '" + req.getName().trim() + "' already exists");
        }
        BusinessUnit saved = businessUnitRepo.save(
                BusinessUnit.builder().name(req.getName().trim()).build());
        return BusinessUnitResponse.from(saved, 0L);
    }

    @PreAuthorize("hasAuthority('organization:EDIT:ALL')")
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

    @PreAuthorize("hasAuthority('organization:EDIT:ALL')")
    @Transactional
    public BusinessUnitResponse toggleBusinessUnitActive(UUID id) {
        BusinessUnit unit = businessUnitRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Business unit not found"));
        unit.setActive(!unit.isActive());
        long count = employeeRepo.countByBusinessUnitId(id);
        return BusinessUnitResponse.from(businessUnitRepo.save(unit), count);
    }

    @PreAuthorize("hasAuthority('organization:DELETE:ALL')")
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

    @PreAuthorize("hasAuthority('organization:CREATE:ALL')")
    @Transactional
    public DepartmentResponse createDepartment(CreateDepartmentRequest req) {
        if (departmentRepo.existsByNameIgnoreCase(req.getName().trim())) {
            throw new IllegalArgumentException("A department named '" + req.getName().trim() + "' already exists");
        }
        Department saved = departmentRepo.save(
                Department.builder().name(req.getName().trim()).build());
        return DepartmentResponse.from(saved, 0L);
    }

    @PreAuthorize("hasAuthority('organization:EDIT:ALL')")
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

    @PreAuthorize("hasAuthority('organization:EDIT:ALL')")
    @Transactional
    public DepartmentResponse toggleDepartmentActive(UUID id) {
        Department dept = departmentRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Department not found"));
        dept.setActive(!dept.isActive());
        long count = employeeRepo.countByDepartmentId(id);
        return DepartmentResponse.from(departmentRepo.save(dept), count);
    }

    // Hard delete: permanently removes the row once no *current* (non-terminated) employee is
    // assigned to it — any leftover employee FK at this point can only belong to an already
    // soft-deleted employee (see clearDepartmentReferences's own comment below).
    @PreAuthorize("hasAuthority('organization:DELETE:ALL')")
    @Transactional
    public void deleteDepartment(UUID id) {
        Department dept = departmentRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Department not found"));
        long count = employeeRepo.countByDepartmentId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    "Cannot delete this department because " + count + " current employee" + (count == 1 ? " is" : "s are")
                            + " assigned to it. Reassign or remove " + (count == 1 ? "that employee" : "those employees") + " first.");
        }
        // No active employee references it — any employees row still pointing at this id belongs
        // to a soft-deleted (terminated) employee; clear that stale, already-nullable FK so the
        // permanent delete below doesn't fail on it. Their own employee/attendance records are
        // untouched.
        employeeRepo.clearDepartmentReferences(id);
        departmentRepo.delete(dept);
    }

    // ── Designations ──────────────────────────────────────────────────────────

    /** Same batched-count fix as listDepartments — see that method's own comment. */
    @Transactional(readOnly = true)
    public List<DesignationResponse> listDesignations() {
        Map<UUID, Long> counts = toCountMap(employeeRepo.countGroupedByDesignationId());
        Map<UUID, String> departmentNames = departmentRepo.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        return designationRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(d -> DesignationResponse.from(d, counts.getOrDefault(d.getId(), 0L), departmentNames.get(d.getDepartmentId())))
                .toList();
    }

    @PreAuthorize("hasAuthority('organization:CREATE:ALL')")
    @Transactional
    public DesignationResponse createDesignation(CreateDesignationRequest req) {
        if (designationRepo.existsByTitleIgnoreCase(req.getTitle().trim())) {
            throw new IllegalArgumentException("A designation titled '" + req.getTitle().trim() + "' already exists");
        }
        Department dept = requireActiveDepartment(req.getDepartmentId());
        Designation saved = designationRepo.save(
                Designation.builder()
                        .title(req.getTitle().trim())
                        .grade(req.getGrade() != null ? req.getGrade().trim() : null)
                        .level(req.getLevel() != null ? req.getLevel().trim() : null)
                        .departmentId(dept.getId())
                        .build());
        return DesignationResponse.from(saved, 0L, dept.getName());
    }

    @PreAuthorize("hasAuthority('organization:EDIT:ALL')")
    @Transactional
    public DesignationResponse updateDesignation(UUID id, UpdateDesignationRequest req) {
        Designation desig = designationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Designation not found"));
        String trimmed = req.getTitle().trim();
        if (!desig.getTitle().equalsIgnoreCase(trimmed) && designationRepo.existsByTitleIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("A designation titled '" + trimmed + "' already exists");
        }
        Department dept = requireActiveDepartment(req.getDepartmentId());
        desig.setTitle(trimmed);
        desig.setGrade(req.getGrade() != null ? req.getGrade().trim() : null);
        desig.setLevel(req.getLevel() != null ? req.getLevel().trim() : null);
        desig.setDepartmentId(dept.getId());
        long count = employeeRepo.countByDesignationId(id);
        return DesignationResponse.from(designationRepo.save(desig), count, dept.getName());
    }

    @PreAuthorize("hasAuthority('organization:EDIT:ALL')")
    @Transactional
    public DesignationResponse toggleDesignationActive(UUID id) {
        Designation desig = designationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Designation not found"));
        desig.setActive(!desig.isActive());
        long count = employeeRepo.countByDesignationId(id);
        String departmentName = desig.getDepartmentId() != null
                ? departmentRepo.findById(desig.getDepartmentId()).map(Department::getName).orElse(null) : null;
        return DesignationResponse.from(designationRepo.save(desig), count, departmentName);
    }

    private Department requireActiveDepartment(UUID departmentId) {
        Department dept = departmentRepo.findById(departmentId)
                .orElseThrow(() -> new IllegalArgumentException("Selected department was not found."));
        if (!dept.isActive())
            throw new IllegalArgumentException("This department is inactive and cannot be assigned. Choose an active department.");
        return dept;
    }

    @PreAuthorize("hasAuthority('organization:DELETE:ALL')")
    @Transactional
    public void deleteDesignation(UUID id) {
        Designation desig = designationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Designation not found"));
        long count = employeeRepo.countByDesignationId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    "Cannot delete this designation because " + count + " current employee" + (count == 1 ? " is" : "s are")
                            + " assigned to it. Reassign or remove " + (count == 1 ? "that employee" : "those employees") + " first.");
        }
        employeeRepo.clearDesignationReferences(id);
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

    // The fixed set of IANA zones a Location may be assigned — Name/City/State/Country stay
    // freely editable (any number of locations can be created), but the one attendance-relevant
    // value must always be one of these, never an arbitrary or malformed zone id. Mirrored on the
    // frontend (OrgSetupPage.tsx's SUPPORTED_LOCATION_TIMEZONES) and DB-enforced independently by
    // the CHECK constraint added in V169/V170 — the same small-fixed-enum convention already used
    // for EMPLOYMENT_TYPES/WORK_MODES elsewhere in this codebase rather than a round-tripped API.
    // Widening this list requires updating both here and that CHECK constraint (a new migration).
    public static final List<String> SUPPORTED_TIMEZONES =
            List.of("Asia/Kolkata", "America/New_York", "America/Chicago", "America/Los_Angeles");

    @PreAuthorize("hasAuthority('organization:CREATE:ALL')")
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

    @PreAuthorize("hasAuthority('organization:EDIT:ALL')")
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
        // Future-effective timezone changes (ONEHR-336 follow-up): unchanged from the Location's
        // current live value saves immediately, no date required — and clears any previously-
        // queued pending change (resubmitting the current value is "cancel the pending change,"
        // not "schedule a no-op"). An actual change requires a future effectiveFrom (today/past
        // rejected) and is written ONLY to the pending pair — the live `timezone` column, and
        // therefore every fresh AttendanceRulesService#resolveEmployeeZoneId resolution before
        // that date, is completely untouched. Never reinterprets any Attendance row already
        // snapshotted under the old zone either way — only a FUTURE, fresh resolution is ever
        // affected (see that method's own Javadoc).
        String newTimezone = validatedTimezone(req.getTimezone());
        if (newTimezone.equals(loc.getTimezone())) {
            loc.setPendingTimezone(null);
            loc.setPendingTimezoneEffectiveFrom(null);
        } else {
            LocalDate today = LocalDate.now();
            if (req.getEffectiveFrom() == null || !req.getEffectiveFrom().isAfter(today)) {
                throw new IllegalArgumentException("Effective From is required and must be a future date (after today) when changing the timezone");
            }
            loc.setPendingTimezone(newTimezone);
            loc.setPendingTimezoneEffectiveFrom(req.getEffectiveFrom());
        }
        long count = employeeRepo.countByLocationId(id);
        return LocationResponse.from(locationRepo.save(loc), count);
    }

    /**
     * Every Location must have exactly one valid timezone, and it must be one of
     * {@link #SUPPORTED_TIMEZONES} — not just any parseable IANA zone id — so a Location +
     * Timezone combination that doesn't correspond to one of the business's actual supported
     * zones (e.g. the pre-existing "Texas" → America/New_York data bug fixed in V169) is no
     * longer representable. Rejects outright rather than falling back to the org-wide default —
     * a typo/unsupported zone here should never surface later as a confusing mismatch deep inside
     * attendance calculations.
     */
    private String validatedTimezone(String timezone) {
        String trimmed = timezone != null ? timezone.trim() : "";
        if (!SUPPORTED_TIMEZONES.contains(trimmed)) {
            throw new IllegalArgumentException(
                    "'" + timezone + "' is not a supported timezone. Choose one of: "
                            + String.join(", ", SUPPORTED_TIMEZONES));
        }
        return trimmed;
    }

    @PreAuthorize("hasAuthority('organization:EDIT:ALL')")
    @Transactional
    public LocationResponse toggleLocationActive(UUID id) {
        Location loc = locationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Location not found"));
        loc.setActive(!loc.isActive());
        long count = employeeRepo.countByLocationId(id);
        return LocationResponse.from(locationRepo.save(loc), count);
    }

    @PreAuthorize("hasAuthority('organization:DELETE:ALL')")
    @Transactional
    public void deleteLocation(UUID id) {
        Location loc = locationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Location not found"));
        long count = employeeRepo.countByLocationId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    "Cannot delete this location because " + count + " current employee" + (count == 1 ? " is" : "s are")
                            + " assigned to it. Reassign or remove " + (count == 1 ? "that employee" : "those employees") + " first.");
        }
        employeeRepo.clearLocationReferences(id);
        // holidays.location_id is NOT NULL (location-owned calendar config, not employee
        // attendance/audit history) — delete rather than null. assets.location_id is nullable —
        // detach instead, preserving the asset record itself.
        holidayRepo.deleteByLocationId(id);
        assetRepo.clearLocationReferences(id);
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
        LocalDate today = LocalDate.now();
        // Default Shift pinned first regardless of how long ago it was created (it was seeded in
        // V95, so a plain createdAt DESC order would otherwise push it to the very bottom of an
        // organization with any real shift history) — see isDefault's own Javadoc.
        return shiftRepo.findAll(Sort.by(Sort.Direction.DESC, "isDefault").and(Sort.by(Sort.Direction.DESC, "createdAt"))).stream()
                .map(s -> ShiftResponse.from(s, shiftVersionResolver.resolveCurrent(s), pendingVersion(s.getId(), today),
                        counts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    /** The full timing history of a Shift, newest first — backs the Shifts tab's version drill-down. */
    @Transactional(readOnly = true)
    public List<ShiftVersionResponse> listShiftVersions(UUID shiftId) {
        if (!shiftRepo.existsById(shiftId)) {
            throw new NoSuchElementException("Shift not found");
        }
        return shiftVersionRepository.findByShiftIdOrderByEffectiveFromDesc(shiftId).stream()
                .map(ShiftVersionResponse::from)
                .toList();
    }

    /** The not-yet-effective version for this shift, if one is scheduled (at most one ever exists — see updateShift). */
    private ShiftVersion pendingVersion(UUID shiftId, LocalDate today) {
        return shiftVersionRepository.findFirstByShiftIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(shiftId, today)
                .orElse(null);
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

    @PreAuthorize("hasAuthority('organization_shifts:CREATE:ALL')")
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
        if (req.getLateGraceMinutes() != null && req.getLateGraceMinutes() < 0) {
            throw new IllegalArgumentException("Grace period cannot be negative");
        }
        validateShiftDuration(req.getStartTime(), req.getEndTime());
        // flexible is still not settable through this API (P2 — see CreateShiftRequest's own
        // comment) — left at its entity default (false).
        Shift saved = shiftRepo.save(Shift.builder()
                .name(trimmedName)
                .code(trimmedCode)
                .description(blankToNull(req.getDescription()))
                .build());
        // A brand-new Shift has no employees assigned yet and no prior configuration to protect —
        // its first version is effective immediately (today), unlike every subsequent change (see
        // updateShift's own future-effective validation). Not a "day one" special case of the
        // Effective From rule; there is simply nothing before it that a same-day version could
        // ever conflict with.
        ShiftVersion version = shiftVersionRepository.save(ShiftVersion.builder()
                .shift(saved)
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .breakMinutes(req.getBreakMinutes())
                .lateGraceMinutes(req.getLateGraceMinutes() != null ? req.getLateGraceMinutes() : DEFAULT_LATE_GRACE_MINUTES)
                .effectiveFrom(LocalDate.now())
                .build());
        return ShiftResponse.from(saved, version, null, 0L);
    }

    @PreAuthorize("hasAuthority('organization_shifts:EDIT:ALL')")
    @Transactional
    public ShiftResponse updateShift(UUID id, UpdateShiftRequest req) {
        Shift shift = shiftRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Shift not found"));
        String trimmedName = req.getName().trim();
        if (!shift.getName().equalsIgnoreCase(trimmedName) && shiftRepo.existsByNameIgnoreCase(trimmedName)) {
            throw new IllegalArgumentException("A shift named '" + trimmedName + "' already exists");
        }
        // The organization's default shift is looked up by this exact, stable name
        // (Shift.DEFAULT_SHIFT_NAME) wherever it's still referenced by name. Renaming it away
        // would silently break that lookup while leaving the (now differently-named) row otherwise
        // intact, so this is blocked outright rather than tolerated as an ordinary edit.
        if (isDefaultShift(shift) && !trimmedName.equals(Shift.DEFAULT_SHIFT_NAME)) {
            throw new IllegalArgumentException(
                    "'" + Shift.DEFAULT_SHIFT_NAME + "' is the organization's default shift and cannot be renamed.");
        }
        String trimmedCode = normalizeCode(req.getCode());
        if (trimmedCode != null && !trimmedCode.equalsIgnoreCase(shift.getCode()) && shiftRepo.existsByCodeIgnoreCase(trimmedCode)) {
            throw new IllegalArgumentException("A shift with code '" + trimmedCode + "' already exists");
        }
        if (req.getBreakMinutes() != null && req.getBreakMinutes() < 0) {
            throw new IllegalArgumentException("Break duration cannot be negative");
        }
        if (req.getLateGraceMinutes() != null && req.getLateGraceMinutes() < 0) {
            throw new IllegalArgumentException("Grace period cannot be negative");
        }
        validateShiftDuration(req.getStartTime(), req.getEndTime());
        // A Shift change is always future-effective — the current configuration must remain in
        // effect through today exactly as it already was; only a future date's Attendance may be
        // governed by the new one. Today itself is deliberately rejected (not just past dates):
        // "today" would be ambiguous for a session already open or already recorded today under
        // the current version — see the Shift Versioning design discussion for why this codebase
        // does not attempt same-day effective dates at all, unlike a raw timestamp scheme would
        // have to. The UI defaults this field to tomorrow and never offers an earlier date, but
        // this is enforced here regardless of what the client actually sends.
        LocalDate today = LocalDate.now();
        if (!req.getEffectiveFrom().isAfter(today)) {
            throw new IllegalArgumentException("Effective From must be a future date (after today)");
        }
        shift.setName(trimmedName);
        shift.setCode(trimmedCode);
        shift.setDescription(blankToNull(req.getDescription()));
        // flexible intentionally remains untouched here (not reset, not updated) — see
        // createShift's own comment; this API doesn't accept it.
        shiftRepo.save(shift);

        // At most one pending (not-yet-effective) version per Shift — editing again before the
        // previously-scheduled change takes effect REPLACES it (delete then insert) rather than
        // stacking a second one, which would leave ambiguous admin intent about which change was
        // actually meant to apply. This never touches any version whose effectiveFrom <= today —
        // those remain exactly as they are, so no already-effective (let alone historical)
        // Attendance/Exception/Penalty/regularization is ever affected by this call.
        shiftVersionRepository.deleteByShiftIdAndEffectiveFromGreaterThan(id, today);
        ShiftVersion pending = shiftVersionRepository.save(ShiftVersion.builder()
                .shift(shift)
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .breakMinutes(req.getBreakMinutes())
                .lateGraceMinutes(req.getLateGraceMinutes() != null ? req.getLateGraceMinutes() : DEFAULT_LATE_GRACE_MINUTES)
                .effectiveFrom(req.getEffectiveFrom())
                .build());

        long count = employeeRepo.countByShiftId(id);
        return ShiftResponse.from(shift, shiftVersionResolver.resolveCurrent(shift), pending, count);
    }

    @PreAuthorize("hasAuthority('organization_shifts:EDIT:ALL')")
    @Transactional
    public ShiftResponse toggleShiftActive(UUID id) {
        Shift shift = shiftRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Shift not found"));
        // The organization's default shift must always be available to newly-created employees —
        // deactivating it (regardless of how many employees currently happen to be assigned to
        // it) would silently leave every subsequent hire without one. Reactivating it is always
        // fine and falls through to the toggle below unaffected.
        if (shift.isActive() && isDefaultShift(shift)) {
            throw new IllegalArgumentException(
                    "'" + Shift.DEFAULT_SHIFT_NAME + "' is the organization's default shift and cannot be deactivated.");
        }
        // D7: a Shift cannot be deactivated while it still governs any employee today or is
        // scheduled to from a future date — only reassignment/cancellation of those assignments
        // (never an automatic one performed here) can clear this guard. A Shift every current
        // assignee has since moved away from (purely historical references only) remains
        // deactivatable — see existsCurrentOrFutureAssignmentForShift's own Javadoc for exactly
        // how "current" is resolved.
        if (shift.isActive()
                && employeeShiftAssignmentRepo.existsCurrentOrFutureAssignmentForShift(id, LocalDate.now())) {
            throw new IllegalStateException(
                    "Cannot deactivate this shift because it is currently assigned to one or more employees, "
                            + "or scheduled to take effect for one in the future. Reassign or cancel those "
                            + "assignments first.");
        }
        shift.setActive(!shift.isActive());
        long count = employeeRepo.countByShiftId(id);
        Shift saved = shiftRepo.save(shift);
        return ShiftResponse.from(saved, shiftVersionResolver.resolveCurrent(saved), pendingVersion(id, LocalDate.now()), count);
    }

    @PreAuthorize("hasAuthority('organization_shifts:DELETE:ALL')")
    @Transactional
    public void deleteShift(UUID id) {
        Shift shift = shiftRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Shift not found"));
        // The organization must always have a default shift to assign new employees to — blocked
        // regardless of employee count (unlike the ordinary guard below), since even a
        // currently-unused default shift is still needed for every future hire.
        if (isDefaultShift(shift)) {
            throw new IllegalArgumentException(
                    "'" + Shift.DEFAULT_SHIFT_NAME + "' is the organization's default shift and cannot be deleted.");
        }
        long count = employeeRepo.countByShiftId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    "Cannot delete this shift because " + count + " current employee" + (count == 1 ? " is" : "s are")
                            + " assigned to it. Reassign or remove " + (count == 1 ? "that employee" : "those employees") + " first.");
        }
        // Independent of (and authoritative over) the display-cache count above: the same
        // effective-dated check toggleShiftActive's D7 guard uses, since Employee.shiftId above can
        // be stale for an employee bulk-reassigned via EmployeeAssignmentService (which never
        // updates that cache — see employeeShiftAssignmentRepo's own field Javadoc). Catches a real
        // current or future-scheduled assignment the cache missed before falling through to the
        // purely-historical guards below.
        if (employeeShiftAssignmentRepo.existsCurrentOrFutureAssignmentForShift(id, LocalDate.now())) {
            throw new IllegalStateException(
                    "Cannot delete this shift because it is currently assigned to one or more employees, "
                            + "or scheduled to take effect for one in the future. Reassign or cancel those "
                            + "assignments first.");
        }
        // Independent of the current-assignment guards above: a Shift with zero current or
        // future-scheduled assignments (everyone since reassigned away) can still be the historical
        // context for real Attendance rows via their snapshotted shiftId (see V163's migration) —
        // deleting it would silently erase that history, exactly the corruption the FK's own
        // ON DELETE RESTRICT exists to prevent. Checked explicitly here (rather than only relying
        // on the raw FK violation) for a clear, actionable message instead of an opaque
        // constraint-violation error. Deactivation remains the supported way to retire a Shift
        // that has real attendance history.
        if (attendanceRepo.existsByShiftId(id)) {
            throw new IllegalStateException(
                    "Cannot delete this shift because attendance records reference it, including historical "
                            + "ones. Deactivate it instead.");
        }
        // Same reasoning as the Attendance guard just above, for the one other table that can still
        // reference this Shift once the current/future-assignment guard above has passed: a purely
        // historical/superseded EmployeeShiftAssignment row (employee_shift_assignments.shift_id has
        // no ON DELETE clause of its own — see V172) would otherwise fail this delete at the raw
        // database FK instead of with a clear, actionable message.
        if (employeeShiftAssignmentRepo.existsByShiftId(id)) {
            throw new IllegalStateException(
                    "Cannot delete this shift because it appears in an employee's shift assignment history. "
                            + "Deactivate it instead.");
        }
        employeeRepo.clearShiftReferences(id);
        // Every ShiftVersion for this shift is removed via the FK's ON DELETE CASCADE (see the
        // V159 migration) — no separate Java-side cleanup needed.
        shiftRepo.delete(shift);
    }

    /**
     * A configured Shift's own span (start to end, overnight-aware) must never exceed the
     * organization's Maximum Shift Day Duration — the same {@link ShiftWeeklyOffRules} value
     * {@link ShiftDayPolicy} uses for the logical-workday-reset boundary, reused here rather than
     * a second, independently-hardcoded 18h limit (the value happens to default to 18h, but this
     * always reads whatever is actually configured). Exactly at the limit is valid; only strictly
     * greater is rejected.
     *
     * <p>Deliberately a standalone check, not a refactor of {@link ShiftDayPolicy}'s own
     * overnight-rollover arithmetic (or {@link ExpectedWorkHoursService#shiftMinutes}'s, which
     * duplicates the same formula for a different purpose) — Shift configuration validation and
     * attendance/logical-workday processing are two separate safeguards that happen to share a
     * limit and a rollover rule, not one one shared code path; this method never touches either of
     * those classes.
     */
    private void validateShiftDuration(LocalTime start, LocalTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        if (!end.isAfter(start)) {
            minutes += 24 * 60; // overnight — rolls into the next calendar day
        }
        double maxHours = shiftWeeklyOffRulesService.getMaximumShiftDayDurationHours();
        if (minutes > Math.round(maxHours * 60)) {
            throw new IllegalArgumentException(
                    "Shift duration cannot exceed the organization's Maximum Shift Day Duration of " + maxHours + " hours");
        }
    }

    /**
     * Whether {@code shift} is the organization's protected Default Shift — the stable
     * {@code is_default} flag (see its own Javadoc) OR the legacy exact-name match, additive so
     * neither check is ever weaker than before: a pre-V232 row somehow missing the backfilled flag
     * (shouldn't happen, but never relied upon) still falls back to the name match, and a row the
     * flag protects remains protected even if its name were ever changed.
     */
    private boolean isDefaultShift(Shift shift) {
        return shift.isDefault() || Shift.DEFAULT_SHIFT_NAME.equals(shift.getName());
    }

    private String normalizeCode(String code) {
        return (code == null || code.isBlank()) ? null : code.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * Comma-separated java.time.DayOfWeek names, e.g. "SATURDAY,SUNDAY" — same convention
     * WeeklyOffPolicy.offDays already uses. Null/empty in, null out — including a non-empty input
     * that normalizes down to nothing (e.g. {@code [" "]}, a list of blank strings only): the
     * empty-string result {@code Collectors.joining} would otherwise produce is deliberately
     * folded into {@code null} too, so every caller's existing {@code == null} rejection already
     * catches it, rather than silently persisting {@code ""} as a "valid" day list.
     */
    private String normalizeDayOfWeekList(List<String> days) {
        if (days == null || days.isEmpty()) return null;
        String joined = days.stream()
                .map(String::trim)
                .filter(d -> !d.isBlank())
                .map(d -> DayOfWeek.valueOf(d.toUpperCase())) // throws IllegalArgumentException on an invalid day name
                .map(Enum::name)
                .distinct()
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    /**
     * Code-review corrective pass, finding 6: at least one working day must always exist for a
     * WeeklyOffPolicy — enforced here (the only place a policy's offDays can ever be written) so
     * a policy with all 7 days off, which would make {@code WorkingDayService#nextWorkingDay}'s
     * search loop unable to ever terminate for any employee on it, can never be saved in the
     * first place. {@code offDays} is already {@link #normalizeDayOfWeekList}'d (deduplicated), so
     * a plain entry count is exact — no need to re-parse into {@link DayOfWeek} here.
     */
    private void assertNotAllSevenDaysOff(String offDays) {
        if (offDays.split(",").length >= DayOfWeek.values().length) {
            throw new IllegalArgumentException(
                    "At least one working day is required — a Weekly Off Policy cannot mark every day of the week off");
        }
    }

    // ── Weekly Off Policies ───────────────────────────────────────────────────
    // WeeklyOffPolicy is the sole source of truth for weekly-off. Its own off-days DEFINITION is
    // now effective-dated via WeeklyOffPolicyVersion (Phase 3) — every date-aware consumer
    // (WorkingDayService, ExceptionService) resolves through WeeklyOffPolicyVersionResolver
    // instead of reading WeeklyOffPolicy.offDays directly; that field remains only as a
    // best-effort display cache of the CURRENT version, kept in sync below. WHICH policy an
    // employee is assigned to is separately effective-dated via EmployeeWeeklyOffPolicyAssignment
    // (see EmployeeAssignmentService) — this class never touches that table, mirroring how it
    // never touches EmployeeShiftAssignment either. No active/inactive concept here (not
    // requested for P1, and not shown in the product's own Weekly Off reference screens) —
    // full-day-only, matching the offDays model already in place.

    @Transactional(readOnly = true)
    public List<WeeklyOffPolicyResponse> listWeeklyOffPolicies() {
        // Audit fix (Phase 3 verification): never employeeRepo.countGroupedByWeeklyOffPolicyId(),
        // which reads Employee.weeklyOffPolicy — a field bulkUpdateWeeklyOff/CSV import no longer
        // write to at all, making it permanently stale for any employee ever reassigned through
        // the new EmployeeWeeklyOffPolicyAssignment table. See that repository's own Javadoc.
        Map<UUID, Long> counts = toCountMap(
                employeeWeeklyOffPolicyAssignmentRepo.countCurrentlyAssignedGroupedByPolicy(LocalDate.now()));
        return weeklyOffPolicyRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(p -> WeeklyOffPolicyResponse.from(p, counts.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    @PreAuthorize("hasAuthority('organization_shifts:CREATE:ALL')")
    @Transactional
    public WeeklyOffPolicyResponse createWeeklyOffPolicy(CreateWeeklyOffPolicyRequest req) {
        String trimmedName = req.getName().trim();
        if (weeklyOffPolicyRepo.existsByNameIgnoreCase(trimmedName)) {
            throw new IllegalArgumentException("A weekly off policy named '" + trimmedName + "' already exists");
        }
        String offDays = normalizeDayOfWeekList(req.getOffDays());
        if (offDays == null) {
            throw new IllegalArgumentException("At least one off day is required");
        }
        assertNotAllSevenDaysOff(offDays);
        WeeklyOffPolicy saved = weeklyOffPolicyRepo.save(WeeklyOffPolicy.builder()
                .name(trimmedName)
                .offDays(offDays)
                .build());
        // A brand-new Policy has no employees assigned yet and no prior configuration to
        // protect — its first version is effective immediately (today), exactly mirroring
        // createShift's identical "day one" reasoning for ShiftVersion.
        weeklyOffPolicyVersionRepo.save(WeeklyOffPolicyVersion.builder()
                .weeklyOffPolicy(saved)
                .offDays(offDays)
                .effectiveFrom(LocalDate.now())
                .build());
        return WeeklyOffPolicyResponse.from(saved, 0L);
    }

    @PreAuthorize("hasAuthority('organization_shifts:EDIT:ALL')")
    @Transactional
    public WeeklyOffPolicyResponse updateWeeklyOffPolicy(UUID id, UpdateWeeklyOffPolicyRequest req) {
        WeeklyOffPolicy policy = weeklyOffPolicyRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Weekly off policy not found"));
        String trimmedName = req.getName().trim();
        if (!policy.getName().equalsIgnoreCase(trimmedName) && weeklyOffPolicyRepo.existsByNameIgnoreCase(trimmedName)) {
            throw new IllegalArgumentException("A weekly off policy named '" + trimmedName + "' already exists");
        }
        String offDays = normalizeDayOfWeekList(req.getOffDays());
        if (offDays == null) {
            throw new IllegalArgumentException("At least one off day is required");
        }
        assertNotAllSevenDaysOff(offDays);
        // Name is plain policy identity (like Shift.name/code/description) and takes effect
        // immediately — only off_days is versioned/effective-dated below.
        policy.setName(trimmedName);
        // A changed off-days definition creates a NEW version rather than mutating history —
        // the previous version (whatever was effective before today) remains intact and keeps
        // governing every date before today exactly as it already did; only today-forward
        // calculations see the new one. Effective TODAY (not admin-chosen, unlike Shift timing
        // edits): a Weekly Off Policy's off-days is a whole-day concept with no "already open
        // session under the old interpretation" ambiguity a same-day Shift-timing edit has, so
        // there is no need for a mandatory future-only date here. At most one version is ever
        // expected effective today-or-later (mirrors shiftVersionRepository's identical "at most
        // one pending version" rule) — a second edit on the SAME calendar day replaces it in
        // place (delete-then-insert) rather than stacking a second one, since nothing could
        // already be attributed to that version within the same calendar day in a way that
        // matters (weekly-off classification is whole-day, not time-of-day). This never touches
        // any version whose effectiveFrom < today — those remain exactly as they are, so no
        // already-effective (let alone historical) calculation is ever affected by this call.
        LocalDate today = LocalDate.now();
        if (!offDays.equals(policy.getOffDays())) {
            weeklyOffPolicyVersionRepo.deleteByWeeklyOffPolicyIdAndEffectiveFromGreaterThanEqual(id, today);
            weeklyOffPolicyVersionRepo.save(WeeklyOffPolicyVersion.builder()
                    .weeklyOffPolicy(policy)
                    .offDays(offDays)
                    .effectiveFrom(today)
                    .build());
            // Display cache kept in sync with the new current version — see its own Javadoc.
            policy.setOffDays(offDays);
        }
        // Audit fix (Phase 3 verification): see listWeeklyOffPolicies' identical comment.
        long count = employeeWeeklyOffPolicyAssignmentRepo.countCurrentlyAssigned(id, LocalDate.now());
        return WeeklyOffPolicyResponse.from(weeklyOffPolicyRepo.save(policy), count);
    }

    @PreAuthorize("hasAuthority('organization_shifts:DELETE:ALL')")
    @Transactional
    public void deleteWeeklyOffPolicy(UUID id) {
        WeeklyOffPolicy policy = weeklyOffPolicyRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Weekly off policy not found"));
        // Audit fix (Phase 3 verification): see listWeeklyOffPolicies' identical comment — this
        // guard must count CURRENT assignments via the authoritative table, never the stale
        // Employee.weeklyOffPolicy cache, or it silently stops blocking deletion for any policy
        // ever reassigned through bulkUpdateWeeklyOff/CSV import.
        long count = employeeWeeklyOffPolicyAssignmentRepo.countCurrentlyAssigned(id, LocalDate.now());
        if (count > 0) {
            throw new IllegalStateException(
                    "Cannot delete this weekly off policy because " + count + " current employee" + (count == 1 ? " is" : "s are")
                            + " assigned to it. Reassign or remove " + (count == 1 ? "that employee" : "those employees") + " first.");
        }
        // Independent of the current-assignment guard above: a Policy with zero employees
        // currently assigned to it (everyone since reassigned away) can still be referenced by
        // assignment history (employee_weekly_off_policy_assignments.weekly_off_policy_id has no
        // ON DELETE clause of its own — see V234) — mirrors deleteShift's identical guard.
        if (employeeWeeklyOffPolicyAssignmentRepo.existsByWeeklyOffPolicyId(id)) {
            throw new IllegalStateException(
                    "Cannot delete this weekly off policy because it appears in an employee's weekly off "
                            + "assignment history.");
        }
        employeeRepo.clearWeeklyOffPolicyReferences(id);
        // Every WeeklyOffPolicyVersion for this policy is removed via the FK's ON DELETE CASCADE
        // (see the V233 migration) — no separate Java-side cleanup needed.
        weeklyOffPolicyRepo.delete(policy);
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

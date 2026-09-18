package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.CreateEmployeeRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.exception.EmployeeCodeConflictException;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies {@link EmployeeService#createEmployee} goes through the centralized
 * {@link EmployeeCodeGenerator} (ONEHR Employee ID rework) instead of any local MAX+1 logic,
 * and that the resulting employee_code is exactly whatever the generator claimed — including
 * propagating a claim conflict as a real failure rather than falling back to a different code.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceCreateTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private DesignationRepository designationRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private EmailService emailService;
    @Mock private LeaveService leaveService;
    @Mock private EmployeeCodeGenerator employeeCodeGenerator;
    @Mock private EmployeeShiftAssignmentRepository employeeShiftAssignmentRepository;
    @Mock private AttendanceProperties attendanceProperties;

    @InjectMocks private EmployeeService employeeService;

    private final String actorEmail = "hradmin@test.com";
    private CreateEmployeeRequest req;

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(UUID.randomUUID()).email(actorEmail).build();
        Role employeeRole = Role.builder().id(1).code("EMPLOYEE").build();

        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(userRepository.existsByEmailAndDeletedAtIsNull(any())).thenReturn(false);
        lenient().when(roleRepository.findByCode("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        lenient().when(passwordEncoder.encode(any())).thenReturn("hashed");
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID());
            return u;
        });
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        // The org-wide business-day clock the "Effective From cannot be in the past" check reads
        // from (see AttendanceProperties.zone's own Javadoc) — never the JVM default.
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");

        req = new CreateEmployeeRequest();
        req.setFullName("Jane Smith");
        req.setEmail("jane@nforceone.com");
        req.setJoiningDate(LocalDate.now());
    }

    @Test
    void createEmployee_usesCodeClaimedByCentralizedGenerator() {
        when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

        var response = employeeService.createEmployee(req, actorEmail);

        assertEquals("NF-2026-0057", response.getEmployeeCode());
        verify(employeeCodeGenerator).claim(req.getEmployeeCode());
    }

    /**
     * No shiftId in the request (the field is optional — see its own comment) — this path never
     * picks a Shift for the employee. A shift-less employee is a valid, permanent state (ONEHR-355
     * fix) — this must never be silently defaulted onto the organization's Default Shift (that
     * fabrication was itself ONEHR-355's root cause), and no EmployeeShiftAssignment row is
     * created either, since there is no Shift to assign.
     */
    @Test
    void createEmployee_leavesEmployeeShiftLess_sinceNoShiftCanBeSpecified() {
        when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        employeeService.createEmployee(req, actorEmail);

        verify(employeeRepository).save(captor.capture());
        assertNull(captor.getValue().getShift());
        verifyNoInteractions(shiftRepository);
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    /**
     * createEmployee supports the same initial Shift assignment UserManagementService#createUser
     * already offers — effective EXACTLY the date the admin chose in the Effective From field,
     * never derived from joining date or "next working day."
     */
    @Test
    void createEmployee_withShiftIdProvided_setsDisplayCacheAndCreatesAssignmentEffectiveOnTheChosenDate() {
        when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");
        com.nforce.onehr.entity.Shift activeShift = com.nforce.onehr.entity.Shift.builder()
                .id(UUID.randomUUID()).name("Morning Shift").active(true).build();
        UUID shiftId = activeShift.getId();
        req.setShiftId(shiftId);
        LocalDate chosenDate = LocalDate.now().plusDays(3);
        req.setEffectiveFrom(chosenDate);
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(activeShift));
        when(employeeShiftAssignmentRepository.save(any(com.nforce.onehr.entity.EmployeeShiftAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        employeeService.createEmployee(req, actorEmail);

        verify(employeeRepository).save(argThat(e -> activeShift.equals(e.getShift())));
        verify(employeeShiftAssignmentRepository).save(argThat(a ->
                activeShift.equals(a.getShift()) && chosenDate.equals(a.getEffectiveFrom())));
    }

    /** Business rule Case 2: today is a VALID Effective From choice. */
    @Test
    void createEmployee_withShiftIdAndEffectiveFromToday_isAccepted() {
        when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");
        com.nforce.onehr.entity.Shift activeShift = com.nforce.onehr.entity.Shift.builder()
                .id(UUID.randomUUID()).name("Morning Shift").active(true).build();
        req.setShiftId(activeShift.getId());
        LocalDate today = LocalDate.now();
        req.setEffectiveFrom(today);
        when(shiftRepository.findById(activeShift.getId())).thenReturn(Optional.of(activeShift));
        when(employeeShiftAssignmentRepository.save(any(com.nforce.onehr.entity.EmployeeShiftAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        employeeService.createEmployee(req, actorEmail);

        verify(employeeShiftAssignmentRepository).save(argThat(a -> today.equals(a.getEffectiveFrom())));
    }

    /**
     * Code-review corrective pass: the "cannot be in the past" check must read the org-wide
     * business-day clock (AttendanceProperties.zone) rather than the JVM default zone — this
     * fails if that dependency is ever removed/bypassed again.
     */
    @Test
    void createEmployee_effectiveFromValidation_readsConfiguredBusinessZone_notJvmDefault() {
        com.nforce.onehr.entity.Shift activeShift = com.nforce.onehr.entity.Shift.builder()
                .id(UUID.randomUUID()).name("Morning Shift").active(true).build();
        req.setShiftId(activeShift.getId());
        req.setEffectiveFrom(LocalDate.now().plusDays(1));
        when(shiftRepository.findById(activeShift.getId())).thenReturn(Optional.of(activeShift));
        when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");
        when(employeeShiftAssignmentRepository.save(any(com.nforce.onehr.entity.EmployeeShiftAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        employeeService.createEmployee(req, actorEmail);

        verify(attendanceProperties, atLeastOnce()).getZone();
    }

    /** Business rule Case 4: a past Effective From is rejected outright. */
    @Test
    void createEmployee_withShiftIdAndPastEffectiveFrom_isRejected() {
        com.nforce.onehr.entity.Shift activeShift = com.nforce.onehr.entity.Shift.builder()
                .id(UUID.randomUUID()).name("Morning Shift").active(true).build();
        req.setShiftId(activeShift.getId());
        req.setEffectiveFrom(LocalDate.now().minusDays(1));
        when(shiftRepository.findById(activeShift.getId())).thenReturn(Optional.of(activeShift));

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    /** Effective From is required whenever a Shift is picked — never silently defaulted. */
    @Test
    void createEmployee_withShiftIdAndNoEffectiveFrom_isRejected() {
        com.nforce.onehr.entity.Shift activeShift = com.nforce.onehr.entity.Shift.builder()
                .id(UUID.randomUUID()).name("Morning Shift").active(true).build();
        req.setShiftId(activeShift.getId());
        when(shiftRepository.findById(activeShift.getId())).thenReturn(Optional.of(activeShift));

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    @Test
    void createEmployee_withInactiveShiftId_isRejected() {
        com.nforce.onehr.entity.Shift inactiveShift = com.nforce.onehr.entity.Shift.builder()
                .id(UUID.randomUUID()).name("Old Shift").active(false).build();
        req.setShiftId(inactiveShift.getId());
        when(shiftRepository.findById(inactiveShift.getId())).thenReturn(Optional.of(inactiveShift));

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    @Test
    void createEmployee_withUnknownShiftId_isRejected() {
        UUID bogusShiftId = UUID.randomUUID();
        req.setShiftId(bogusShiftId);
        when(shiftRepository.findById(bogusShiftId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void createEmployee_passesSubmittedPreviewCodeThroughToGenerator() {
        req.setEmployeeCode("NF-2026-0056");
        when(employeeCodeGenerator.claim("NF-2026-0056")).thenReturn("NF-2026-0056");

        var response = employeeService.createEmployee(req, actorEmail);

        assertEquals("NF-2026-0056", response.getEmployeeCode());
        verify(employeeCodeGenerator).claim("NF-2026-0056");
    }

    @Test
    void createEmployee_generatorConflict_failsWithoutPersistingEmployee() {
        req.setEmployeeCode("NF-2026-0056");
        when(employeeCodeGenerator.claim("NF-2026-0056"))
                .thenThrow(new EmployeeCodeConflictException("NF-2026-0056"));

        assertThrows(EmployeeCodeConflictException.class,
                () -> employeeService.createEmployee(req, actorEmail));

        verify(employeeRepository, never()).save(any());
    }

    @Test
    void previewNextEmployeeCode_delegatesToGenerator() {
        when(employeeCodeGenerator.preview()).thenReturn("NF-2026-0057");

        assertEquals("NF-2026-0057", employeeService.previewNextEmployeeCode());
    }

    // ── Finalized Location/Timezone model: Location is the ONLY timezone input ──
    // CreateEmployeeRequest has no timezone field at all (see its own class comment) — these
    // lock in that an invalid/inactive/timezone-less Location is rejected outright rather than
    // silently accepted, since Location is now the sole source of the employee's effective
    // attendance timezone.

    @Test
    void createEmployee_rejectsALocationIdThatDoesNotExist() {
        UUID bogusLocationId = UUID.randomUUID();
        req.setLocationId(bogusLocationId);
        when(locationRepository.findById(bogusLocationId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void createEmployee_rejectsAnInactiveLocation() {
        UUID locationId = UUID.randomUUID();
        Location inactive = Location.builder().id(locationId).name("Hyderabad").timezone("Asia/Kolkata").active(false).build();
        req.setLocationId(locationId);
        when(locationRepository.findById(locationId)).thenReturn(Optional.of(inactive));

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
    }

    /**
     * Should be unreachable through the normal Org Setup flow (OrgService#createLocation always
     * validates the timezone against a fixed supported set — see SUPPORTED_TIMEZONES), but this
     * is the hard backstop the feature explicitly requires: a Location with no valid timezone
     * must never be assignable.
     */
    @Test
    void createEmployee_rejectsALocationWithNoTimezoneConfigured() {
        UUID locationId = UUID.randomUUID();
        Location noTimezone = Location.builder().id(locationId).name("Legacy Office").timezone(null).active(true).build();
        req.setLocationId(locationId);
        when(locationRepository.findById(locationId)).thenReturn(Optional.of(noTimezone));

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void createEmployee_acceptsAnActiveLocationWithAValidTimezone() {
        UUID locationId = UUID.randomUUID();
        Location valid = Location.builder().id(locationId).name("Hyderabad").timezone("Asia/Kolkata").active(true).build();
        req.setLocationId(locationId);
        when(locationRepository.findById(locationId)).thenReturn(Optional.of(valid));
        when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        employeeService.createEmployee(req, actorEmail);

        verify(employeeRepository).save(captor.capture());
        assertEquals(locationId, captor.getValue().getLocation().getId());
    }
}

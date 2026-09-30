package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.CreateUserRequest;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.UpdateUserRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.exception.EmployeeCodeConflictException;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.security.ForceLogoutBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests around Super Admin updateUser behavior: token-version invalidation,
 * force-logout broadcast, and targeted employee notifications for actual profile changes.
 */
@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

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
    @Mock private NotificationService notificationService;
    @Mock private LeaveService leaveService;
    @Mock private ForceLogoutBroadcaster forceLogoutBroadcaster;
    @Mock private EmployeeCodeGenerator employeeCodeGenerator;
    @Mock private EmployeeService employeeService;
    @Mock private EmployeeShiftAssignmentRepository employeeShiftAssignmentRepository;
    @Mock private EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver;
    @Mock private AttendanceProperties attendanceProperties;

    @InjectMocks private UserManagementService userManagementService;

    private final UUID actorId = UUID.randomUUID();
    private final UUID targetUserId = UUID.randomUUID();
    private final String actorEmail = "admin@test.com";
    private final UUID currentDepartmentId = UUID.randomUUID();
    private final UUID newDepartmentId = UUID.randomUUID();
    private final UUID currentDesignationId = UUID.randomUUID();
    private final UUID newDesignationId = UUID.randomUUID();
    private final UUID currentLocationId = UUID.randomUUID();
    private final UUID newLocationId = UUID.randomUUID();
    private final UUID currentManagerId = UUID.randomUUID();
    private final UUID newManagerId = UUID.randomUUID();
    private final UUID activeShiftId = UUID.randomUUID();
    private final UUID inactiveShiftId = UUID.randomUUID();

    private User targetUser;
    private Employee targetEmployee;
    private Department currentDepartment;
    private Department newDepartment;
    private Designation currentDesignation;
    private Designation newDesignation;
    private Location currentLocation;
    private Location newLocation;
    private Shift activeShift;
    private Shift inactiveShift;

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(actorId).email(actorEmail).build();
        targetUser = User.builder().id(targetUserId).email("target@test.com")
                .roles(new LinkedHashSet<>(Set.of(Role.builder().id(1).code("EMPLOYEE").build())))
                .tokenVersion(3)
                .build();
        currentDepartment = Department.builder().id(currentDepartmentId).name("Operations").build();
        newDepartment = Department.builder().id(newDepartmentId).name("Finance").build();
        currentDesignation = Designation.builder().id(currentDesignationId).title("Analyst").build();
        newDesignation = Designation.builder().id(newDesignationId).title("Senior Analyst").build();
        currentLocation = Location.builder().id(currentLocationId).name("Hyderabad").timezone("Asia/Kolkata").build();
        newLocation = Location.builder().id(newLocationId).name("Bengaluru").timezone("Asia/Kolkata").build();
        targetEmployee = Employee.builder().userId(targetUserId).user(targetUser)
                .fullName("Target User").employmentType("FULL_TIME").workMode("ONSITE")
                .department(currentDepartment).designation(currentDesignation).location(currentLocation)
                .joiningDate(LocalDate.now()).build();

        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(employeeRepository.findById(targetUserId)).thenReturn(Optional.of(targetEmployee));
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(departmentRepository.findById(currentDepartmentId)).thenReturn(Optional.of(currentDepartment));
        lenient().when(departmentRepository.findById(newDepartmentId)).thenReturn(Optional.of(newDepartment));
        lenient().when(designationRepository.findById(currentDesignationId)).thenReturn(Optional.of(currentDesignation));
        lenient().when(designationRepository.findById(newDesignationId)).thenReturn(Optional.of(newDesignation));
        lenient().when(locationRepository.findById(currentLocationId)).thenReturn(Optional.of(currentLocation));
        lenient().when(locationRepository.findById(newLocationId)).thenReturn(Optional.of(newLocation));

        activeShift = Shift.builder().id(activeShiftId).name("Day Shift").active(true).build();
        inactiveShift = Shift.builder().id(inactiveShiftId).name("Retired Shift").active(false).build();
        lenient().when(shiftRepository.findById(activeShiftId)).thenReturn(Optional.of(activeShift));
        lenient().when(shiftRepository.findById(inactiveShiftId)).thenReturn(Optional.of(inactiveShift));
        // The org-wide business-day clock the "Effective From cannot be in the past" check reads
        // from (see AttendanceProperties.zone's own Javadoc) — never the JVM default.
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
    }

    private UpdateUserRequest requestWithRole(String roleCode) {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(roleCode);
        return req;
    }

    private void assertForcedLogout(UpdateUserRequest req) {
        userManagementService.updateUser(targetUserId, req, actorEmail);

        assertEquals(4, targetUser.getTokenVersion());
        verify(userRepository).save(targetUser);
        verify(forceLogoutBroadcaster).forceLogout(targetUserId);
    }

    @Test
    void updateUser_roleChange_bumpsTokenVersionAndForcesLogout() {
        Role hrAdmin = Role.builder().id(2).code("HR_ADMIN").build();
        when(roleRepository.findByCode("HR_ADMIN")).thenReturn(Optional.of(hrAdmin));

        userManagementService.updateUser(targetUserId, requestWithRole("hr_admin"), actorEmail);

        assertEquals(4, targetUser.getTokenVersion());
        verify(userRepository).save(targetUser);
        verify(forceLogoutBroadcaster).forceLogout(targetUserId);
        verify(notificationService).send(eq(targetUserId), eq("ACCOUNT"), eq("Role Updated"), any(), any());
    }

    @Test
    void updateUser_fullNameChange_forcesLogoutWithoutRoleNotification() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setFullName("New Name");

        assertForcedLogout(req);

        verify(notificationService, never()).send(eq(targetUserId), eq("ACCOUNT"), eq("Role Updated"), any(), any());
    }

    @Test
    void updateUser_employmentTypeChange_forcesLogout() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setEmploymentType("CONTRACT");

        assertForcedLogout(req);
    }

    @Test
    void updateUser_workModeChange_forcesLogout() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setWorkMode("REMOTE");

        assertForcedLogout(req);
    }

    @Test
    void updateUser_departmentChange_forcesLogout() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setDepartmentId(newDepartmentId);

        assertForcedLogout(req);
    }

    @Test
    void updateUser_designationChange_forcesLogout() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setDesignationId(newDesignationId);

        assertForcedLogout(req);
    }

    // TEMPORARY (ONEHR-336 follow-up): Shift and Location reassignment via Employee update is
    // disabled for now — see UserManagementService.updateUser's own guard comment for why
    // (pending a proper reassignment flow that correctly effective-dates attendance-relevant
    // history). Employee CREATION (createUser) is unaffected by this restriction — see the
    // CreateUser nested test class below, whose shift/location assignment tests are untouched.

    @Test
    void updateUser_locationChange_isRejected() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setLocationId(newLocationId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userManagementService.updateUser(targetUserId, req, actorEmail));

        assertTrue(ex.getMessage().contains("Location"));
        assertEquals(currentLocation, targetEmployee.getLocation());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(forceLogoutBroadcaster);
    }

    @Test
    void updateUser_unchangedLocation_isStillAllowed_doesNotThrowOrForceLogout() {
        // Resubmitting the SAME location already on the employee (e.g. an edit form that always
        // sends the current value) must keep working — only a genuine change is rejected.
        UpdateUserRequest req = new UpdateUserRequest();
        req.setLocationId(currentLocationId);

        userManagementService.updateUser(targetUserId, req, actorEmail);

        assertEquals(currentLocation, targetEmployee.getLocation());
        assertEquals(3, targetUser.getTokenVersion());
        verifyNoInteractions(forceLogoutBroadcaster);
    }

    @Test
    void updateUser_shiftChange_isRejected() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setShiftId(activeShiftId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userManagementService.updateUser(targetUserId, req, actorEmail));

        assertTrue(ex.getMessage().contains("Shift"));
        assertNull(targetEmployee.getShift());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(forceLogoutBroadcaster);
    }

    // Employee no longer carries a timezone field at all — the finalized Location/Timezone model
    // (see Employee's own class Javadoc) makes Location the sole source of an employee's
    // effective attendance timezone, so there is nothing left here to admin-set/reject/clear.
    // See LocationValidationTest for the create/update rejection of an invalid/inactive/
    // timezone-less Location, and AttendanceRulesServiceTest for the resolution chain itself.

    // The temporary blanket restriction above rejects EVERY actual shift change, including one
    // that targets an inactive shift — the active/inactive distinction this test originally
    // exercised is now moot (unreachable: the restriction throws before any active/inactive check
    // would even run), but the outcome (throws, no modification, no force logout) is unchanged, so
    // this is kept as regression coverage for that outcome specifically.
    @Test
    void updateUser_changingToInactiveShift_throwsAndDoesNotModifyEmployeeOrForceLogout() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setShiftId(inactiveShiftId);

        assertThrows(IllegalArgumentException.class,
                () -> userManagementService.updateUser(targetUserId, req, actorEmail));

        assertNull(targetEmployee.getShift());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(forceLogoutBroadcaster);
    }

    // The employee already being on a shift that's since been deactivated (shiftId unchanged in
    // this request) must keep working — this is a no-op re-save, not a new assignment, and must
    // never be blocked by the inactive-shift guard above.
    @Test
    void updateUser_unchangedShift_evenIfNowInactive_doesNotThrowOrForceLogout() {
        targetEmployee.setShift(inactiveShift);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setShiftId(inactiveShiftId);

        userManagementService.updateUser(targetUserId, req, actorEmail);

        assertEquals(inactiveShift, targetEmployee.getShift());
        assertEquals(3, targetUser.getTokenVersion());
        verifyNoInteractions(forceLogoutBroadcaster);
    }

    @Test
    void updateUser_managerChange_forcesLogoutAndSendsManagerUpdatedNotification() {
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(targetUserId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder()
                        .employeeUserId(targetUserId)
                        .managerUserId(currentManagerId)
                        .build()));

        UpdateUserRequest req = new UpdateUserRequest();
        req.setManagerId(newManagerId);

        assertForcedLogout(req);

        verify(historyRepository).closeCurrentEntry(eq(targetUserId), any());
        verify(historyRepository).save(argThat(h ->
                targetUserId.equals(h.getEmployeeUserId())
                        && newManagerId.equals(h.getManagerUserId())
                        && actorId.equals(h.getChangedBy())));
        verify(notificationService).send(targetUserId, "ACCOUNT", "Manager Updated", "Your manager has been updated.", "/profile");
        verify(notificationService, never()).send(eq(targetUserId), eq("ACCOUNT"), eq("Role Updated"), any(), any());
    }

    @Test
    void updateUser_unchangedManager_sendsNoManagerNotificationAndDoesNotForceLogout() {
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(targetUserId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder()
                        .employeeUserId(targetUserId)
                        .managerUserId(currentManagerId)
                        .build()));

        UpdateUserRequest req = new UpdateUserRequest();
        req.setManagerId(currentManagerId);

        userManagementService.updateUser(targetUserId, req, actorEmail);

        assertEquals(3, targetUser.getTokenVersion());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(forceLogoutBroadcaster);
        verify(historyRepository, never()).closeCurrentEntry(any(), any());
        verify(historyRepository, never()).save(any(EmployeeManagerHistory.class));
        verify(notificationService, never()).send(eq(targetUserId), eq("ACCOUNT"), eq("Manager Updated"), any(), any());
    }

    @Test
    void updateUser_unchangedProfile_doesNotForceLogout() {
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(targetUserId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder()
                        .employeeUserId(targetUserId)
                        .managerUserId(currentManagerId)
                        .build()));

        UpdateUserRequest req = new UpdateUserRequest();
        req.setFullName("Target User");
        req.setRole("EMPLOYEE");
        req.setDepartmentId(currentDepartmentId);
        req.setDesignationId(currentDesignationId);
        req.setLocationId(currentLocationId);
        req.setEmploymentType("FULL_TIME");
        req.setWorkMode("ONSITE");
        req.setManagerId(currentManagerId);

        userManagementService.updateUser(targetUserId, req, actorEmail);

        assertEquals(3, targetUser.getTokenVersion());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(forceLogoutBroadcaster);
        verify(notificationService, never()).send(eq(targetUserId), eq("ACCOUNT"), eq("Role Updated"), any(), any());
        verify(notificationService, never()).send(eq(targetUserId), eq("ACCOUNT"), eq("Manager Updated"), any(), any());
    }

    @Test
    void updateUser_nonRoleProfileChange_doesNotSendRoleUpdatedNotification() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setWorkMode("HYBRID");

        assertForcedLogout(req);

        verify(notificationService, never()).send(eq(targetUserId), eq("ACCOUNT"), eq("Role Updated"), any(), any());
    }

    @Test
    void updateUser_invalidRole_throwsWithoutBumpingTokenVersionOrForceLogout() {
        assertThrows(IllegalArgumentException.class,
                () -> userManagementService.updateUser(targetUserId, requestWithRole("NOT_A_ROLE"), actorEmail));

        assertEquals(3, targetUser.getTokenVersion());
        verifyNoInteractions(forceLogoutBroadcaster);
    }

    // Admin-initiated reset (ONEHR-179): must also invalidate any session issued under the old
    // password, same as the self-service changePassword/forgotPassword paths in AuthService.
    @Test
    void resetPassword_bumpsTokenVersion() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
        when(passwordEncoder.encode(anyString())).thenReturn("temp-hash");

        userManagementService.resetPassword(targetUserId, actorEmail);

        assertEquals(4, targetUser.getTokenVersion());
        assertTrue(targetUser.isMustChangePassword());
        verify(userRepository).save(targetUser);
    }

    // ONEHR-351: the admin-triggered Password Reset notification must not carry a linkPath,
    // so the Notifications tab renders no "Open related page" action for it. The reset email
    // itself (EmailService) is untouched by this change.
    @Test
    void resetPassword_sendsNotificationWithNoRelatedPageLink() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
        when(passwordEncoder.encode(anyString())).thenReturn("temp-hash");

        userManagementService.resetPassword(targetUserId, actorEmail);

        verify(notificationService).send(eq(targetUserId), eq("SECURITY"),
                eq("Password Reset by Administrator"), anyString(), isNull());
    }

    // Employee ID rework (ONEHR): createUser must go through the centralized
    // EmployeeCodeGenerator instead of any local MAX+1 lookup — see also EmployeeServiceCreateTest
    // for the equivalent coverage on EmployeeService#createEmployee.
    @Nested
    class CreateUser {

        private CreateUserRequest req;

        @BeforeEach
        void setUp() {
            User actor = User.builder().id(actorId).email(actorEmail).build();
            Role employeeRole = Role.builder().id(1).code("EMPLOYEE").build();

            lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
            lenient().when(userRepository.existsByEmailAndDeletedAtIsNull(any())).thenReturn(false);
            lenient().when(roleRepository.findByCode("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
            lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                if (u.getId() == null) u.setId(targetUserId);
                return u;
            });
            lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(employeeShiftAssignmentRepository.save(any(EmployeeShiftAssignment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            req = new CreateUserRequest();
            req.setFullName("Jane Smith");
            req.setEmail("jane@nforceone.com");
            req.setRole("EMPLOYEE");
            req.setJoiningDate(LocalDate.now());
        }

        @Test
        void usesCodeClaimedByCentralizedGenerator() {
            when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

            var response = userManagementService.createUser(req, actorEmail);

            assertEquals("NF-2026-0057", response.getEmployeeCode());
            verify(employeeCodeGenerator).claim(req.getEmployeeCode());
        }

        @Test
        void passesSubmittedPreviewCodeThroughToGenerator() {
            req.setEmployeeCode("NF-2026-0056");
            when(employeeCodeGenerator.claim("NF-2026-0056")).thenReturn("NF-2026-0056");

            var response = userManagementService.createUser(req, actorEmail);

            assertEquals("NF-2026-0056", response.getEmployeeCode());
            verify(employeeCodeGenerator).claim("NF-2026-0056");
        }

        @Test
        void generatorConflict_failsWithoutPersistingEmployee() {
            req.setEmployeeCode("NF-2026-0056");
            when(employeeCodeGenerator.claim("NF-2026-0056"))
                    .thenThrow(new EmployeeCodeConflictException("NF-2026-0056"));

            assertThrows(EmployeeCodeConflictException.class,
                    () -> userManagementService.createUser(req, actorEmail));

            verify(employeeRepository, never()).save(any());
        }

        // A brand-new employee can never have a legitimate pre-existing assignment to an
        // inactive shift, so unlike updateUser's guard this one is unconditional — see
        // UserManagementService.createUser's own shiftId branch.
        @Test
        void assigningInactiveShift_throwsWithoutPersistingEmployee() {
            req.setShiftId(inactiveShiftId);
            when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

            assertThrows(IllegalArgumentException.class,
                    () -> userManagementService.createUser(req, actorEmail));

            verify(employeeRepository, never()).save(any());
        }

        /**
         * Employee.shift is set immediately (display/roster cache only); the authoritative
         * EmployeeShiftAssignment is created effective EXACTLY the date the admin chose in the
         * Effective From field — never derived from joining date or "next working day" (the
         * previous, incorrect rule this replaces — see the business rule's own worked examples).
         */
        @Test
        void assigningActiveShift_succeeds_andCreatesAssignmentEffectiveOnTheChosenDate() {
            req.setShiftId(activeShiftId);
            LocalDate chosenDate = LocalDate.now().plusDays(3);
            req.setEffectiveFrom(chosenDate);
            when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

            userManagementService.createUser(req, actorEmail);

            verify(employeeRepository).save(argThat(e -> activeShift.equals(e.getShift())));
            verify(employeeShiftAssignmentRepository).save(argThat(a ->
                    activeShift.equals(a.getShift())
                            && chosenDate.equals(a.getEffectiveFrom())
                            && targetUserId.equals(a.getEmployeeUserId())));
        }

        /** Business rule Case 2: today is a VALID choice — the assignment is effective today itself. */
        @Test
        void assigningActiveShift_effectiveToday_isAccepted() {
            req.setShiftId(activeShiftId);
            LocalDate today = LocalDate.now();
            req.setEffectiveFrom(today);
            when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

            userManagementService.createUser(req, actorEmail);

            verify(employeeShiftAssignmentRepository).save(argThat(a -> today.equals(a.getEffectiveFrom())));
        }

        /**
         * Business rule Case 1: an Effective From date that happens to be a weekly off is honored
         * exactly as selected — never silently moved to the next working day. (This test doesn't
         * itself configure a weekly-off policy; it documents that nothing here would even look at
         * one — the chosen date is persisted verbatim regardless.)
         */
        @Test
        void assigningActiveShift_effectiveDateOnAWeeklyOff_isHonoredExactly_neverMovedToNextWorkingDay() {
            req.setShiftId(activeShiftId);
            LocalDate saturday = LocalDate.now().with(java.time.DayOfWeek.SATURDAY).isBefore(LocalDate.now())
                    ? LocalDate.now().with(java.time.DayOfWeek.SATURDAY).plusWeeks(1) : LocalDate.now().with(java.time.DayOfWeek.SATURDAY);
            req.setEffectiveFrom(saturday);
            when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

            userManagementService.createUser(req, actorEmail);

            verify(employeeShiftAssignmentRepository).save(argThat(a -> saturday.equals(a.getEffectiveFrom())));
        }

        /**
         * Code-review corrective pass: the "cannot be in the past" check must read the org-wide
         * business-day clock (AttendanceProperties.zone) rather than the JVM default zone — this
         * fails if that dependency is ever removed/bypassed again.
         */
        @Test
        void assigningActiveShift_effectiveFromValidation_readsConfiguredBusinessZone_notJvmDefault() {
            req.setShiftId(activeShiftId);
            req.setEffectiveFrom(LocalDate.now().plusDays(1));
            when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

            userManagementService.createUser(req, actorEmail);

            verify(attendanceProperties, atLeastOnce()).getZone();
        }

        /** Business rule Case 4: a past Effective From is rejected outright. */
        @Test
        void assigningActiveShift_rejectsPastEffectiveFrom() {
            req.setShiftId(activeShiftId);
            req.setEffectiveFrom(LocalDate.now().minusDays(1));

            assertThrows(IllegalArgumentException.class, () -> userManagementService.createUser(req, actorEmail));

            verify(employeeRepository, never()).save(any());
            verifyNoInteractions(employeeShiftAssignmentRepository);
        }

        /** Effective From is required whenever a Shift is picked — never silently defaulted to "today". */
        @Test
        void assigningActiveShift_rejectsMissingEffectiveFrom() {
            req.setShiftId(activeShiftId);

            assertThrows(IllegalArgumentException.class, () -> userManagementService.createUser(req, actorEmail));

            verify(employeeRepository, never()).save(any());
            verifyNoInteractions(employeeShiftAssignmentRepository);
        }

        /**
         * No shiftId in the request at all — a shift-less employee is now a valid, permanent state
         * (ONEHR-355 fix): this must never be silently defaulted onto the organization's Default
         * Shift (that fabrication was itself ONEHR-355's root cause), and no
         * EmployeeShiftAssignment row is created either, since there is no Shift to assign. No
         * Effective From is required either, since there is no Shift to make effective.
         */
        @Test
        void noShiftSpecified_leavesEmployeeShiftLess() {
            when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

            userManagementService.createUser(req, actorEmail);

            verify(employeeRepository).save(argThat(e -> e.getShift() == null));
            verifyNoInteractions(employeeShiftAssignmentRepository);
        }
    }

    // N+1 fix: listUsers() must resolve every employee's current manager via
    // EmployeeService#findCurrentManagersBulk (one bulk call) instead of calling
    // findCurrentManager once per employee (up to 3 extra queries each).
    @Nested
    class ListUsers {

        private final UUID emp1Id = UUID.randomUUID();
        private final UUID emp2Id = UUID.randomUUID();
        private final UUID emp3Id = UUID.randomUUID();
        private final UUID managerId = UUID.randomUUID();

        private Employee employeeWithManager(UUID id, String name) {
            User user = User.builder().id(id).email(name.toLowerCase().replace(' ', '.') + "@test.com")
                    .roles(new LinkedHashSet<>(Set.of(Role.builder().id(1).code("EMPLOYEE").build())))
                    .build();
            return Employee.builder().userId(id).user(user).employeeCode("NF-" + id)
                    .fullName(name).employmentType("FULL_TIME").workMode("ONSITE")
                    .joiningDate(LocalDate.now()).build();
        }

        @Test
        void resolvesManagersInOneBulkCall_insteadOfPerEmployee() {
            Employee emp1 = employeeWithManager(emp1Id, "Employee One");
            Employee emp2 = employeeWithManager(emp2Id, "Employee Two");
            when(employeeRepository.findAllWithDetails()).thenReturn(List.of(emp1, emp2));
            when(employeeService.findCurrentManagersBulk(any())).thenReturn(Map.of());

            userManagementService.listUsers();

            verify(employeeService, times(1)).findCurrentManagersBulk(argThat(ids ->
                    new HashSet<>(ids).equals(Set.of(emp1Id, emp2Id))));
            // The per-row lookups findCurrentManager() would have used — none of them should be
            // hit for a list operation now that the bulk path is used instead.
            verifyNoInteractions(historyRepository);
            verify(userRepository, never()).findById(any());
        }

        @Test
        void attachesBulkResolvedManager_toMatchingEmployee() {
            Employee emp1 = employeeWithManager(emp1Id, "Employee One");
            Employee emp2 = employeeWithManager(emp2Id, "Employee Two");
            when(employeeRepository.findAllWithDetails()).thenReturn(List.of(emp1, emp2));

            EmployeeResponse.ManagerRef manager = EmployeeResponse.ManagerRef.builder()
                    .userId(managerId.toString()).fullName("Manager Person").email("manager@test.com").build();
            when(employeeService.findCurrentManagersBulk(any()))
                    .thenReturn(Map.of(emp1Id, manager));

            var results = userManagementService.listUsers();

            assertEquals(manager, results.get(0).getCurrentManager());
            // emp2 has no entry in the bulk map — must resolve to no manager, not an error.
            assertNull(results.get(1).getCurrentManager());
        }

        @Test
        void preservesEmployeeOrderingFromFindAllWithDetails() {
            Employee emp1 = employeeWithManager(emp1Id, "Employee One");
            Employee emp2 = employeeWithManager(emp2Id, "Employee Two");
            Employee emp3 = employeeWithManager(emp3Id, "Employee Three");
            when(employeeRepository.findAllWithDetails()).thenReturn(List.of(emp2, emp3, emp1));
            when(employeeService.findCurrentManagersBulk(any())).thenReturn(Map.of());

            var results = userManagementService.listUsers();

            assertEquals(List.of("Employee Two", "Employee Three", "Employee One"),
                    results.stream().map(EmployeeResponse::getFullName).toList());
        }

        @Test
        void employeeWithNoManagerHistory_hasNullCurrentManager() {
            Employee emp1 = employeeWithManager(emp1Id, "Employee One");
            when(employeeRepository.findAllWithDetails()).thenReturn(List.of(emp1));
            // Bulk lookup deliberately omits emp1 — no manager-history row for them.
            when(employeeService.findCurrentManagersBulk(any())).thenReturn(Map.of());

            var results = userManagementService.listUsers();

            assertNull(results.get(0).getCurrentManager());
        }
    }

    /**
     * Code-review fix: the Super Admin response's shiftId/shiftName must read the employee's
     * CURRENTLY-effective {@link EmployeeShiftAssignment} (via {@link EmployeeShiftAssignmentResolver}),
     * never the stale {@code Employee.shift} display cache that Bulk-Edit Team Assignment/CSV
     * import (EmployeeAssignmentService#assignShift) do not keep in sync.
     */
    @Nested
    class EffectiveShiftInResponse {

        @Test
        void activeAssignment_isReflectedInTheResponse() {
            when(employeeShiftAssignmentResolver.resolveIfPresent(eq(targetUserId), any(LocalDate.class)))
                    .thenReturn(Optional.of(EmployeeShiftAssignment.builder()
                            .employeeUserId(targetUserId).shift(activeShift).effectiveFrom(LocalDate.now().minusDays(5)).build()));

            EmployeeResponse response = userManagementService.setActiveStatus(targetUserId, true, actorEmail);

            assertEquals(activeShiftId.toString(), response.getShiftId());
            assertEquals("Day Shift", response.getShiftName());
        }

        /**
         * A real, existing assignment whose effectiveFrom hasn't arrived yet simply isn't returned
         * by resolveIfPresent (its own "as of this date" contract) — the response must show no
         * shift at all, never the not-yet-effective one, until its Effective From date arrives.
         */
        @Test
        void pendingFutureAssignment_doesNotAppearActiveBeforeItsEffectiveFromDate() {
            when(employeeShiftAssignmentResolver.resolveIfPresent(eq(targetUserId), any(LocalDate.class)))
                    .thenReturn(Optional.empty());

            EmployeeResponse response = userManagementService.setActiveStatus(targetUserId, true, actorEmail);

            assertNull(response.getShiftId());
            assertNull(response.getShiftName());
        }

        @Test
        void noShiftAssigned_returnsNullShift() {
            targetEmployee.setShift(null);
            when(employeeShiftAssignmentResolver.resolveIfPresent(eq(targetUserId), any(LocalDate.class)))
                    .thenReturn(Optional.empty());

            EmployeeResponse response = userManagementService.setActiveStatus(targetUserId, true, actorEmail);

            assertNull(response.getShiftId());
            assertNull(response.getShiftName());
        }

        /** Employee.shift is a best-effort display cache only — a resolver value that disagrees with it must win. */
        @Test
        void staleEmployeeShiftCache_isIgnored_authoritativeAssignmentWins() {
            targetEmployee.setShift(inactiveShift); // stale cache: still points at the OLD shift
            when(employeeShiftAssignmentResolver.resolveIfPresent(eq(targetUserId), any(LocalDate.class)))
                    .thenReturn(Optional.of(EmployeeShiftAssignment.builder()
                            .employeeUserId(targetUserId).shift(activeShift).effectiveFrom(LocalDate.now()).build()));

            EmployeeResponse response = userManagementService.setActiveStatus(targetUserId, true, actorEmail);

            assertEquals(activeShiftId.toString(), response.getShiftId());
            assertEquals("Day Shift", response.getShiftName());
        }
    }
}

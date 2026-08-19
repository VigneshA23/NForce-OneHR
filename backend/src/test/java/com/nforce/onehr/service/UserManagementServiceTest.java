package com.nforce.onehr.service;

import com.nforce.onehr.dto.UpdateUserRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.security.ForceLogoutBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private EmailService emailService;
    @Mock private NotificationService notificationService;
    @Mock private LeaveService leaveService;
    @Mock private ForceLogoutBroadcaster forceLogoutBroadcaster;

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

    private User targetUser;
    private Employee targetEmployee;
    private Department currentDepartment;
    private Department newDepartment;
    private Designation currentDesignation;
    private Designation newDesignation;
    private Location currentLocation;
    private Location newLocation;

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
        currentLocation = Location.builder().id(currentLocationId).name("Hyderabad").build();
        newLocation = Location.builder().id(newLocationId).name("Bengaluru").build();
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

    @Test
    void updateUser_locationChange_forcesLogout() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setLocationId(newLocationId);

        assertForcedLogout(req);
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
}

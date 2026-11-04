package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.CreateUserRequest;
import com.nforce.onehr.dto.UpdateUserRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.ForceLogoutBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Role-assignment security rules shared by every assignment path (Manage Roles, createUser's role,
 * updateUser's role field) and the "can't manage a more privileged account" ceiling on the other
 * user-management writes. Uses the REAL EffectivePermissionService over {@link PermissionFixture},
 * so union / inactive-role / ceiling semantics are exercised, not stubbed.
 */
@ExtendWith(MockitoExtension.class)
class UserManagementServiceRolesTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private ManagerRoleSyncService managerRoleSyncService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private NotificationService notificationService;
    @Mock private ForceLogoutBroadcaster forceLogoutBroadcaster;
    @Mock private EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private RolePermissionRepository rolePermissionRepository;

    @InjectMocks private UserManagementService service;

    private final PermissionFixture f = new PermissionFixture();
    private User superAdmin;
    private User hrAdmin;
    private User employee;

    @BeforeEach
    void setUp() {
        f.bind(rolePermissionRepository);
        ReflectionTestUtils.setField(service, "effectivePermissionService", new EffectivePermissionService(rolePermissionRepository));

        superAdmin = user("sa@test.com", f.superAdmin, f.employee);
        user("ua@test.com", f.userAdmin, f.employee);
        hrAdmin = user("hr@test.com", f.hrAdmin, f.employee);
        employee = user("emp@test.com", f.employee);

        for (Role r : List.of(f.employee, f.manager, f.hrAdmin, f.superAdmin, f.delivery, f.userAdmin, f.inactiveCustom)) {
            lenient().when(roleRepository.findById(r.getId())).thenReturn(Optional.of(r));
            lenient().when(roleRepository.findByCode(r.getCode())).thenReturn(Optional.of(r));
        }
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
    }

    private User user(String email, Role... roles) {
        User u = User.builder().id(UUID.randomUUID()).email(email).active(true)
                .roles(new HashSet<>(Set.of(roles))).build();
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(u));
        lenient().when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        Employee e = Employee.builder().userId(u.getId()).user(u).fullName(email).joiningDate(LocalDate.now()).build();
        lenient().when(employeeRepository.findById(u.getId())).thenReturn(Optional.of(e));
        return u;
    }

    private static Set<String> codes(User u) {
        return u.getRoles().stream().map(Role::getCode).collect(java.util.stream.Collectors.toSet());
    }

    // ─── Manage Roles (setUserRoles) ───────────────────────────────────────────

    @Test
    void superAdmin_assignsMultipleRoles_keepsEmployee_invalidatesSession_andAudits() {
        int tv = employee.getTokenVersion();

        service.setUserRoles(employee.getId(), List.of(f.manager.getId(), f.userAdmin.getId()), "sa@test.com");

        assertEquals(Set.of("EMPLOYEE", "MANAGER", "CUSTOM_USER_ADMIN"), codes(employee));
        assertEquals(tv + 1, employee.getTokenVersion());
        assertEquals("PROFILE_UPDATED", employee.getTokenVersionReason());
        verify(auditService).log(eq(superAdmin.getId()), eq("ROLE_CHANGED"), eq(employee.getId()), anyString(), anyString());
    }

    @Test
    void unchangedRoleSet_isANoOp() {
        service.setUserRoles(employee.getId(), List.of(f.employee.getId()), "sa@test.com");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void duplicateRoleIds_areCollapsed() {
        service.setUserRoles(employee.getId(), List.of(f.manager.getId(), f.manager.getId()), "sa@test.com");

        assertEquals(Set.of("EMPLOYEE", "MANAGER"), codes(employee));
    }

    @Test
    void cannotChangeOwnRoles() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.setUserRoles(superAdmin.getId(), List.of(f.superAdmin.getId(), f.manager.getId()), "sa@test.com"));
        assertTrue(e.getMessage().contains("your own roles"));
    }

    @Test
    void lesserAdmin_cannotGrantSuperAdmin_privilegeEscalation() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setUserRoles(employee.getId(), List.of(f.superAdmin.getId()), "ua@test.com"));
        assertEquals(Set.of("EMPLOYEE"), codes(employee));
        verify(userRepository, never()).save(any());
    }

    @Test
    void lesserAdmin_cannotStripRolesFromAMorePrivilegedAccount() {
        // Removing HR Admin needs HR Admin's permissions in hand too (leave:APPROVE:ALL etc.).
        assertThrows(IllegalArgumentException.class,
                () -> service.setUserRoles(hrAdmin.getId(), List.of(), "ua@test.com"));
        assertTrue(codes(hrAdmin).contains("HR_ADMIN"));
    }

    @Test
    void lesserAdmin_canAssignARoleWithinTheirOwnCeiling() {
        service.setUserRoles(employee.getId(), List.of(f.userAdmin.getId()), "ua@test.com");

        assertTrue(codes(employee).contains("CUSTOM_USER_ADMIN"));
    }

    @Test
    void actorWithoutRoleAssignmentPermission_isDenied() {
        assertThrows(AccessDeniedException.class,
                () -> service.setUserRoles(employee.getId(), List.of(f.manager.getId()), "hr@test.com"));
    }

    @Test
    void inactiveRole_cannotBeNewlyAssigned() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.setUserRoles(employee.getId(), List.of(f.inactiveCustom.getId()), "sa@test.com"));
        assertTrue(e.getMessage().contains("inactive"));
    }

    @Test
    void placeholderSystemRole_isNotAssignable() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setUserRoles(employee.getId(), List.of(f.delivery.getId()), "sa@test.com"));
    }

    @Test
    void unknownRoleId_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setUserRoles(employee.getId(), List.of(999), "sa@test.com"));
    }

    @Test
    void lastActiveSuperAdmin_cannotLoseSuperAdmin() {
        User otherSa = user("sa2@test.com", f.superAdmin, f.employee);
        when(userRepository.findActiveSuperAdmins()).thenReturn(List.of(otherSa));

        // Only the target is an ACTIVE Super Admin (the actor's own SA role is irrelevant here, e.g. a
        // deactivated colleague's lingering row) — removing it would leave nobody.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.setUserRoles(otherSa.getId(), List.of(f.hrAdmin.getId()), "sa@test.com"));
        assertTrue(e.getMessage().contains("last active Super Admin"));
    }

    @Test
    void superAdminCanBeRemoved_whenAnotherActiveSuperAdminRemains() {
        User otherSa = user("sa2@test.com", f.superAdmin, f.employee);
        when(userRepository.findActiveSuperAdmins()).thenReturn(List.of(superAdmin, otherSa));

        service.setUserRoles(otherSa.getId(), List.of(f.hrAdmin.getId()), "sa@test.com");

        assertEquals(Set.of("EMPLOYEE", "HR_ADMIN"), codes(otherSa));
    }

    @Test
    void autoGrantedManager_isKeptEvenIfOmitted() {
        employee.getRoles().add(f.manager);
        doAnswer(inv -> { inv.<Set<Role>>getArgument(1).add(f.manager); return null; })
                .when(managerRoleSyncService).restoreIfAutoGranted(eq(employee.getId()), any());

        service.setUserRoles(employee.getId(), List.of(f.userAdmin.getId()), "sa@test.com");

        assertEquals(Set.of("EMPLOYEE", "MANAGER", "CUSTOM_USER_ADMIN"), codes(employee));
    }

    // ─── Other user-management paths must not bypass the same rules ────────────

    @Test
    void createUser_withSuperAdminRole_byLesserAdmin_isRejectedBeforeAnythingIsSaved() {
        CreateUserRequest req = new CreateUserRequest();
        req.setEmail("new@test.com");
        req.setFullName("New Person");
        req.setRole("SUPER_ADMIN");

        assertThrows(IllegalArgumentException.class, () -> service.createUser(req, "ua@test.com"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_roleField_cannotChangeOwnRole() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole("EMPLOYEE");

        assertThrows(IllegalArgumentException.class, () -> service.updateUser(superAdmin.getId(), req, "sa@test.com"));
        assertTrue(codes(superAdmin).contains("SUPER_ADMIN"));
    }

    @Test
    void updateUser_primaryRoleChange_keepsCustomRoles() {
        employee.getRoles().add(f.userAdmin);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole("MANAGER");

        service.updateUser(employee.getId(), req, "sa@test.com");

        assertEquals(Set.of("EMPLOYEE", "MANAGER", "CUSTOM_USER_ADMIN"), codes(employee));
    }

    @Test
    void resetPassword_onAMorePrivilegedAccount_isRejected() {
        int tv = superAdmin.getTokenVersion();

        assertThrows(IllegalArgumentException.class, () -> service.resetPassword(superAdmin.getId(), "ua@test.com"));
        assertEquals(tv, superAdmin.getTokenVersion());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void updateUser_onAMorePrivilegedAccount_isRejected() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setEmail("attacker@test.com");

        assertThrows(IllegalArgumentException.class, () -> service.updateUser(superAdmin.getId(), req, "ua@test.com"));
        assertEquals("sa@test.com", superAdmin.getEmail());
    }

    @Test
    void inactiveRole_contributesNothing_toTheCeiling() {
        // The target's only grant beyond ua's own (user_management:DELETE) comes from an INACTIVE
        // role — inactive grants nothing, so the target is not "more privileged" than ua.
        employee.getRoles().add(f.inactiveCustom);
        service.resetPassword(employee.getId(), "ua@test.com");
        verify(passwordEncoder).encode(anyString());
    }
}

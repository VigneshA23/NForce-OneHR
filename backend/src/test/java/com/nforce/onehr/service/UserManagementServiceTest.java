package com.nforce.onehr.service;

import com.nforce.onehr.dto.UpdateUserRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
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
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests, same isolation approach as LeaveServiceTest — focused on the
 * token-version bump + force-logout broadcast added to updateUser's role-change branch (server
 * auto-logout on role change). Does not attempt full coverage of updateUser's other fields.
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

    private User targetUser;
    private Employee targetEmployee;

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(actorId).email(actorEmail).build();
        targetUser = User.builder().id(targetUserId).email("target@test.com")
                .roles(new HashSet<>(Set.of(Role.builder().id(1).code("EMPLOYEE").build())))
                .tokenVersion(3)
                .build();
        targetEmployee = Employee.builder().userId(targetUserId).user(targetUser)
                .fullName("Target User").employmentType("FULL_TIME").workMode("ONSITE")
                .joiningDate(LocalDate.now()).build();

        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(employeeRepository.findById(targetUserId)).thenReturn(Optional.of(targetEmployee));
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
    }

    private UpdateUserRequest requestWithRole(String roleCode) {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(roleCode);
        return req;
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
    void updateUser_nonRoleFieldChange_doesNotBumpTokenVersionOrForceLogout() {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setFullName("New Name");

        userManagementService.updateUser(targetUserId, req, actorEmail);

        assertEquals(3, targetUser.getTokenVersion());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(forceLogoutBroadcaster);
    }

    @Test
    void updateUser_invalidRole_throwsWithoutBumpingTokenVersionOrForceLogout() {
        assertThrows(IllegalArgumentException.class,
                () -> userManagementService.updateUser(targetUserId, requestWithRole("NOT_A_ROLE"), actorEmail));

        assertEquals(3, targetUser.getTokenVersion());
        verifyNoInteractions(forceLogoutBroadcaster);
    }
}

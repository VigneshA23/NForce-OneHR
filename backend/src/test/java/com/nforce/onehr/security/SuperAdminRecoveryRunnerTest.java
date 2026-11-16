package com.nforce.onehr.security;

import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.RoleRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SuperAdminRecoveryRunnerTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    private SuperAdminRecoveryRunner runner;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runner = new SuperAdminRecoveryRunner(userRepository, roleRepository, auditService, auditSnapshot);
    }

    private User user(boolean active) {
        return User.builder().id(id).email("sa@t").active(active).roles(new HashSet<>()).build();
    }

    @Test
    void disabledByDefault_doesNothing() {
        assertFalse(runner.recover());
        verifyNoInteractions(userRepository, roleRepository, auditService);
    }

    @Test
    void anActiveSuperAdminExists_nothingHappens_evenWhenConfigured() {
        ReflectionTestUtils.setField(runner, "recoveryEmail", "sa@t");
        when(userRepository.findActiveSuperAdmins()).thenReturn(List.of(user(true)));

        assertFalse(runner.recover());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void noActiveSuperAdmin_grantsTheRoleToTheNamedActiveUser_andAudits() {
        ReflectionTestUtils.setField(runner, "recoveryEmail", " sa@t ");
        User u = user(true);
        Role role = Role.builder().id(4).code("SUPER_ADMIN").displayName("Super Admin").build();
        when(userRepository.findActiveSuperAdmins()).thenReturn(List.of());
        when(userRepository.findByEmail("sa@t")).thenReturn(Optional.of(u));
        when(roleRepository.findByCode("SUPER_ADMIN")).thenReturn(Optional.of(role));
        when(auditSnapshot.toJson(any())).thenReturn("{}");

        assertTrue(runner.recover());

        assertTrue(u.getRoles().contains(role));
        verify(userRepository).save(u);
        verify(auditService).log(eq(id), eq("ROLE_SUPER_ADMIN_RECOVERED"), eq(id), any(), any());
    }

    @Test
    void anInactiveOrUnknownUser_isNeverPromoted() {
        ReflectionTestUtils.setField(runner, "recoveryEmail", "sa@t");
        when(userRepository.findActiveSuperAdmins()).thenReturn(List.of());
        when(userRepository.findByEmail("sa@t")).thenReturn(Optional.of(user(false)));

        assertFalse(runner.recover());
        verify(userRepository, never()).save(any());

        when(userRepository.findByEmail("sa@t")).thenReturn(Optional.empty());
        assertFalse(runner.recover());
    }
}

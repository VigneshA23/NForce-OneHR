package com.nforce.onehr.service;

import com.nforce.onehr.entity.ManagerRoleAutoGrant;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.ManagerRoleAutoGrantRepository;
import com.nforce.onehr.repository.RoleRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests (no Spring context, no DB). Focused on the provenance contract: an
 * auto-grant is only ever revoked by this service, never a manually-assigned MANAGER role — see
 * ManagerRoleAutoGrant's own Javadoc and the Phase 4 decision record this class implements.
 */
@ExtendWith(MockitoExtension.class)
class ManagerRoleSyncServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private ManagerRoleAutoGrantRepository autoGrantRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @InjectMocks private ManagerRoleSyncService service;

    private static final UUID MANAGER_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private static Role managerRole() {
        return Role.builder().id(2).code("MANAGER").build();
    }

    @Test
    void grantIfNewManager_userWithoutManagerRole_addsRoleAndMarksAutoGrant() {
        User manager = User.builder().id(MANAGER_ID).roles(new HashSet<>()).build();
        when(userRepository.findById(MANAGER_ID)).thenReturn(Optional.of(manager));
        when(roleRepository.findByCode("MANAGER")).thenReturn(Optional.of(managerRole()));
        when(auditSnapshot.toJson(any())).thenReturn("{}");

        service.grantIfNewManager(MANAGER_ID, ACTOR_ID);

        assertTrue(manager.getRoles().stream().anyMatch(r -> "MANAGER".equals(r.getCode())));
        verify(userRepository).save(manager);
        verify(autoGrantRepository).save(argThat(g -> g.getUserId().equals(MANAGER_ID)));
        verify(auditService).log(eq(ACTOR_ID), eq("ROLE_AUTO_GRANTED"), eq(MANAGER_ID), any(), any());
    }

    @Test
    void grantIfNewManager_alreadyHoldsManagerRole_isNoOp() {
        User manager = User.builder().id(MANAGER_ID).roles(new HashSet<>(Set.of(managerRole()))).build();
        when(userRepository.findById(MANAGER_ID)).thenReturn(Optional.of(manager));

        service.grantIfNewManager(MANAGER_ID, ACTOR_ID);

        verify(userRepository, never()).save(any());
        verify(autoGrantRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void grantIfNewManager_nullManagerId_isNoOp() {
        service.grantIfNewManager(null, ACTOR_ID);

        verifyNoInteractions(userRepository, autoGrantRepository, auditService);
    }

    @Test
    void revokeIfNoLongerManager_autoGrantedAndZeroReportsLeft_removesRoleAndMarker() {
        User manager = User.builder().id(MANAGER_ID).roles(new HashSet<>(Set.of(managerRole()))).build();
        when(autoGrantRepository.existsById(MANAGER_ID)).thenReturn(true);
        when(historyRepository.findCurrentDirectReportIds(MANAGER_ID)).thenReturn(List.of());
        when(userRepository.findById(MANAGER_ID)).thenReturn(Optional.of(manager));
        when(roleRepository.findByCode("MANAGER")).thenReturn(Optional.of(managerRole()));
        when(auditSnapshot.toJson(any())).thenReturn("{}");

        service.revokeIfNoLongerManager(MANAGER_ID, ACTOR_ID);

        assertTrue(manager.getRoles().isEmpty());
        verify(userRepository).save(manager);
        verify(autoGrantRepository).deleteById(MANAGER_ID);
        verify(auditService).log(eq(ACTOR_ID), eq("ROLE_AUTO_REVOKED"), eq(MANAGER_ID), any(), any());
    }

    @Test
    void revokeIfNoLongerManager_noAutoGrantMarker_neverTouchesManuallyAssignedRole() {
        when(autoGrantRepository.existsById(MANAGER_ID)).thenReturn(false);

        service.revokeIfNoLongerManager(MANAGER_ID, ACTOR_ID);

        verifyNoInteractions(userRepository, auditService);
        verify(historyRepository, never()).findCurrentDirectReportIds(any());
    }

    @Test
    void revokeIfNoLongerManager_stillHasOtherReports_isNoOp() {
        when(autoGrantRepository.existsById(MANAGER_ID)).thenReturn(true);
        when(historyRepository.findCurrentDirectReportIds(MANAGER_ID)).thenReturn(List.of(UUID.randomUUID()));

        service.revokeIfNoLongerManager(MANAGER_ID, ACTOR_ID);

        verifyNoInteractions(userRepository, auditService);
    }

    @Test
    void restoreIfAutoGranted_markerExists_reAddsManagerRole() {
        User user = User.builder().id(MANAGER_ID).roles(new HashSet<>()).build();
        when(autoGrantRepository.existsById(MANAGER_ID)).thenReturn(true);
        when(roleRepository.findByCode("MANAGER")).thenReturn(Optional.of(managerRole()));

        service.restoreIfAutoGranted(user);

        assertTrue(user.getRoles().stream().anyMatch(r -> "MANAGER".equals(r.getCode())));
    }

    @Test
    void restoreIfAutoGranted_noMarker_leavesRolesUntouched() {
        User user = User.builder().id(MANAGER_ID).roles(new HashSet<>()).build();
        when(autoGrantRepository.existsById(MANAGER_ID)).thenReturn(false);

        service.restoreIfAutoGranted(user);

        assertTrue(user.getRoles().isEmpty());
        verifyNoInteractions(roleRepository);
    }
}

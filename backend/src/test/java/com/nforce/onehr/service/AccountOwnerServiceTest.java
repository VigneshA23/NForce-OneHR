package com.nforce.onehr.service;

import com.nforce.onehr.entity.PermissionModule;
import com.nforce.onehr.entity.SuperAdminModuleFence;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.PermissionModuleRepository;
import com.nforce.onehr.repository.SuperAdminModuleFenceRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests. Account Owner is a standalone flag on the actor, independent of the
 * role_permissions engine — these tests focus on that gate (non-owners rejected regardless of
 * what role they hold, including SUPER_ADMIN) and the fence create/remove flow.
 */
@ExtendWith(MockitoExtension.class)
class AccountOwnerServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PermissionModuleRepository moduleRepository;
    @Mock private SuperAdminModuleFenceRepository fenceRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @InjectMocks private AccountOwnerService accountOwnerService;

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final String OWNER_EMAIL = "owner@test.com";
    private static final String NON_OWNER_EMAIL = "superadmin@test.com";

    private static User owner() {
        return User.builder().id(OWNER_ID).email(OWNER_EMAIL).accountOwner(true).build();
    }

    private static User nonOwner() {
        return User.builder().id(UUID.randomUUID()).email(NON_OWNER_EMAIL).accountOwner(false).build();
    }

    @Test
    void listFences_nonOwner_isRejectedRegardlessOfRole() {
        when(userRepository.findByEmail(NON_OWNER_EMAIL)).thenReturn(Optional.of(nonOwner()));

        assertThrows(AccessDeniedException.class, () -> accountOwnerService.listFences(NON_OWNER_EMAIL));
        verifyNoInteractions(fenceRepository);
    }

    @Test
    void listFences_owner_returnsFences() {
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner()));
        when(fenceRepository.findAllWithModule()).thenReturn(List.of());

        assertEquals(List.of(), accountOwnerService.listFences(OWNER_EMAIL));
    }

    @Test
    void fenceModule_owner_createsFenceAndAudits() {
        PermissionModule module = PermissionModule.builder().id(1).code("payroll").displayName("Payroll").build();
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner()));
        when(moduleRepository.findByCode("payroll")).thenReturn(Optional.of(module));
        when(fenceRepository.findByModule_CodeAndAction("payroll", "*")).thenReturn(Optional.empty());
        when(fenceRepository.save(any(SuperAdminModuleFence.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditSnapshot.toJson(any())).thenReturn("{}");

        SuperAdminModuleFence result = accountOwnerService.fenceModule("payroll", "*", OWNER_EMAIL);

        assertEquals("payroll", result.getModule().getCode());
        assertEquals(OWNER_ID, result.getFencedBy());
        verify(auditService).log(eq(OWNER_ID), eq("MODULE_FENCE_APPLIED"), eq(OWNER_ID), any(), any());
    }

    @Test
    void fenceModule_alreadyFenced_rejected() {
        PermissionModule module = PermissionModule.builder().id(1).code("payroll").displayName("Payroll").build();
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner()));
        when(moduleRepository.findByCode("payroll")).thenReturn(Optional.of(module));
        when(fenceRepository.findByModule_CodeAndAction("payroll", "*"))
                .thenReturn(Optional.of(SuperAdminModuleFence.builder().id(1L).action("*").build()));

        assertThrows(IllegalArgumentException.class, () -> accountOwnerService.fenceModule("payroll", "*", OWNER_EMAIL));
        verify(fenceRepository, never()).save(any());
    }

    @Test
    void fenceModule_nonOwner_isRejectedBeforeTouchingModuleRepository() {
        when(userRepository.findByEmail(NON_OWNER_EMAIL)).thenReturn(Optional.of(nonOwner()));

        assertThrows(AccessDeniedException.class, () -> accountOwnerService.fenceModule("payroll", "*", NON_OWNER_EMAIL));
        verifyNoInteractions(moduleRepository, fenceRepository);
    }

    @Test
    void unfenceModule_owner_deletesFenceAndAudits() {
        SuperAdminModuleFence existing = SuperAdminModuleFence.builder().id(9L).action("CONFIGURE").build();
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner()));
        when(fenceRepository.findByModule_CodeAndAction("leave", "CONFIGURE")).thenReturn(Optional.of(existing));
        when(auditSnapshot.toJson(any())).thenReturn("{}");

        accountOwnerService.unfenceModule("leave", "CONFIGURE", OWNER_EMAIL);

        verify(fenceRepository).delete(existing);
        verify(auditService).log(eq(OWNER_ID), eq("MODULE_FENCE_LIFTED"), eq(OWNER_ID), any(), any());
    }

    @Test
    void unfenceModule_notCurrentlyFenced_rejected() {
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner()));
        when(fenceRepository.findByModule_CodeAndAction("leave", "CONFIGURE")).thenReturn(Optional.empty());

        assertThrows(java.util.NoSuchElementException.class,
                () -> accountOwnerService.unfenceModule("leave", "CONFIGURE", OWNER_EMAIL));
        verify(fenceRepository, never()).delete(any());
    }
}

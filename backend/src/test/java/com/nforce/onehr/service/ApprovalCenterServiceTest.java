package com.nforce.onehr.service;

import com.nforce.onehr.dto.asset.AssetRequestResponse;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Which module queues the Approval Center fetches for a caller: by APPROVE permission per module
 * (the old Manager/HR/Super Admin gate for system roles), so a custom role sees exactly the
 * modules it was granted (assets via V242's assets:APPROVE). FAQs & Guides stay on the role gate —
 * that approval follows the author's manager chain.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalCenterServiceTest {

    private static final String EMAIL = "actor@test.com";

    @Mock private LeaveService leaveService;
    @Mock private RegularizationService regularizationService;
    @Mock private ExpenseService expenseService;
    @Mock private AssetService assetService;
    @Mock private AttendanceRequestService attendanceRequestService;
    @Mock private OvertimeRequestService overtimeRequestService;
    @Mock private HelpContentService helpContentService;
    @Mock private UserRepository userRepo;
    @Spy private EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(Map.of(
            "CUSTOM_OVERTIME", Map.of("overtime:APPROVE", "TEAM"),
            "CUSTOM_EXPENSE_TEAM", Map.of("expenses:APPROVE", "TEAM"),
            "CUSTOM_EXPENSE_ALL", Map.of("expenses:APPROVE", "ALL"),
            "CUSTOM_NOTHING", Map.of("leave:VIEW", "ALL"),
            "CUSTOM_ASSET_TEAM", Map.of("assets:APPROVE", "TEAM")));

    @InjectMocks private ApprovalCenterService service;

    private void actorWith(String... codes) {
        int[] customId = {100};
        User actor = User.builder().id(UUID.randomUUID()).email(EMAIL).roles(Arrays.stream(codes)
                .map(c -> c.startsWith("CUSTOM_")
                        ? Role.builder().id(customId[0]++).code(c).displayName(c).build()
                        : SeedMatrix.role(c))
                .collect(Collectors.toSet())).build();
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(actor));
    }

    private void verifyModuleQueues(boolean leave, boolean regularization, boolean wfh, boolean overtime) {
        verify(leaveService, times(leave ? 1 : 0)).listPendingApprovals(EMAIL);
        verify(regularizationService, times(regularization ? 1 : 0)).listPendingForApprover(EMAIL);
        verify(attendanceRequestService, times(wfh ? 1 : 0)).listPendingForApprover(EMAIL);
        verify(overtimeRequestService, times(overtime ? 1 : 0)).listPendingForApprover(EMAIL);
    }

    @Test
    void employee_getsNothing() {
        actorWith("EMPLOYEE");

        assertTrue(service.pendingApprovals(EMAIL).isEmpty());
        verifyModuleQueues(false, false, false, false);
        verifyNoInteractions(expenseService, assetService, helpContentService);
    }

    @Test
    void manager_getsEveryQueue_expenseAtManagerStage() {
        actorWith("EMPLOYEE", "MANAGER");

        service.pendingApprovals(EMAIL);

        verifyModuleQueues(true, true, true, true);
        verify(expenseService).pendingForManager(EMAIL);
        verify(expenseService, never()).pendingForFinalApprover(any());
        verify(assetService).listPendingForApprover(EMAIL);
        verify(helpContentService).listPendingApprovalsForApprover(EMAIL);
    }

    @Test
    void hrAdminAndSuperAdmin_getEveryQueue_expenseAtBothStages_assetsPendingOnly() {
        for (String code : List.of("HR_ADMIN", "SUPER_ADMIN")) {
            clearInvocations(leaveService, regularizationService, expenseService, assetService,
                    attendanceRequestService, overtimeRequestService, helpContentService);
            actorWith("EMPLOYEE", code);
            when(assetService.listPendingForApprover(EMAIL)).thenReturn(List.of(asset(1L, "PENDING"), asset(2L, "APPROVED")));

            List<String> ids = service.pendingApprovals(EMAIL).stream().map(i -> i.getId()).toList();

            assertEquals(List.of("1"), ids, code);
            verifyModuleQueues(true, true, true, true);
            verify(expenseService).pendingForFinalApprover(EMAIL);
            verify(expenseService, never()).pendingForManager(any());
            verify(helpContentService).listPendingApprovalsForApprover(EMAIL);
        }
    }

    @Test
    void managerWhoIsAlsoHrAdmin_getsPendingAssetsOnly_likeAnyHrAdmin() {
        actorWith("EMPLOYEE", "MANAGER", "HR_ADMIN");
        when(assetService.listPendingForApprover(EMAIL)).thenReturn(List.of(asset(1L, "PENDING"), asset(2L, "APPROVED")));

        assertEquals(1, service.pendingApprovals(EMAIL).size());
        verify(assetService, times(1)).listPendingForApprover(EMAIL);
        verify(expenseService).pendingForFinalApprover(EMAIL);
        verify(expenseService, never()).pendingForManager(any());
    }

    @Test
    void customRole_getsOnlyTheQueuesItWasGranted() {
        actorWith("EMPLOYEE", "CUSTOM_OVERTIME");

        service.pendingApprovals(EMAIL);

        verifyModuleQueues(false, false, false, true);
        // Holding a custom role means it's asked whether that role is a Workflow Studio expense stage.
        verify(expenseService).hasFinalApproverQueue(EMAIL);
        verifyNoMoreInteractions(expenseService);
        verifyNoInteractions(assetService, helpContentService);
    }

    @Test
    void customRoleNamedAsAnExpenseStage_getsThatStagesQueue() {
        actorWith("EMPLOYEE", "CUSTOM_NOTHING");
        when(expenseService.hasFinalApproverQueue(EMAIL)).thenReturn(true);

        service.pendingApprovals(EMAIL);

        verify(expenseService).pendingForFinalApprover(EMAIL);
        verify(expenseService, never()).pendingForManager(any());
    }

    @Test
    void customRole_withTeamAssetApproval_getsItsReportsAssetRequests() {
        actorWith("EMPLOYEE", "CUSTOM_ASSET_TEAM");
        when(assetService.listPendingForApprover(EMAIL)).thenReturn(List.of(asset(1L, "PENDING")));

        assertEquals(1, service.pendingApprovals(EMAIL).size());
        verify(assetService).listPendingForApprover(EMAIL);
    }

    @Test
    void customRole_expenseStageFollowsScope() {
        actorWith("EMPLOYEE", "CUSTOM_EXPENSE_TEAM");
        service.pendingApprovals(EMAIL);
        verify(expenseService).pendingForManager(EMAIL);
        verify(expenseService, never()).pendingForFinalApprover(any());

        clearInvocations(expenseService);
        actorWith("EMPLOYEE", "CUSTOM_EXPENSE_ALL");
        service.pendingApprovals(EMAIL);
        verify(expenseService).pendingForFinalApprover(EMAIL);
        verify(expenseService, never()).pendingForManager(any());
    }

    @Test
    void customRoleWithoutAnyApprovePermission_getsNothing() {
        actorWith("EMPLOYEE", "CUSTOM_NOTHING");

        assertTrue(service.pendingApprovals(EMAIL).isEmpty());
        verifyModuleQueues(false, false, false, false);
        verify(expenseService).hasFinalApproverQueue(EMAIL);
        verifyNoMoreInteractions(expenseService);
        verifyNoInteractions(assetService, helpContentService);
    }

    private static AssetRequestResponse asset(Long id, String status) {
        return AssetRequestResponse.builder().id(id).status(status).createdAt(Instant.now()).build();
    }
}

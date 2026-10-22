package com.nforce.onehr.service;

import com.nforce.onehr.dto.asset.AssetResponse;
import com.nforce.onehr.dto.asset.CreateAssetRequest;
import com.nforce.onehr.entity.Asset;
import com.nforce.onehr.entity.AssetAssignment;
import com.nforce.onehr.entity.AssetCategory;
import com.nforce.onehr.entity.AssetRequest;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    @Mock private AssetRepository assetRepo;
    @Mock private AssetCategoryRepository categoryRepo;
    @Mock private AssetAssignmentRepository assignmentRepo;
    @Mock private AssetRequestRepository requestRepo;
    @Mock private EmployeeManagerHistoryRepository historyRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private LocationRepository locationRepo;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private NotificationService notificationService;
    // Real seed matrix (V225..V242) plus custom roles: one holding every assets permission, one
    // holding only assets:APPROVE:TEAM, one holding an unrelated permission only.
    @Spy private EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(Map.of(
            "CUSTOM_ASSETS", Map.of("assets:VIEW", "ALL", "assets:CREATE", "ALL", "assets:EDIT", "ALL", "assets:APPROVE", "ALL"),
            "CUSTOM_TEAM_APPROVER", Map.of("assets:APPROVE", "TEAM"),
            "CUSTOM_OTHER", Map.of("holidays:VIEW", "ALL")));

    @InjectMocks
    private AssetService assetService;

    private final String adminEmail = "admin@test.com";
    private User adminUser;
    private AssetCategory category;

    @BeforeEach
    void setUp() {
        adminUser = User.builder().id(UUID.randomUUID()).email(adminEmail)
                .roles(Set.of(SeedMatrix.role("HR_ADMIN")))
                .build();
        category = AssetCategory.builder().id(1).name("Laptop").build();

        lenient().when(userRepo.findByEmail(adminEmail)).thenReturn(Optional.of(adminUser));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
    }

    private Asset assetWithStatus(String status) {
        return Asset.builder().id(1L).assetTag("A-1").category(category).status(status).build();
    }

    // ── ONEHR bug: "Assets Assigned" tile overcounted retired-but-still-open assignments ──

    @Test
    void retireAsset_currentlyAssigned_closesTheOpenAssignment() {
        Asset asset = assetWithStatus("ASSIGNED");
        AssetAssignment openAssignment = AssetAssignment.builder()
                .id(11L).assetId(1L).employeeUserId(UUID.randomUUID()).build();

        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset));
        // First call is retireAsset's own close-the-assignment lookup; toAssetResponse's own
        // re-lookup afterward must see it as no longer open — same as a real DB round-trip would.
        when(assignmentRepo.findByAssetIdAndEffectiveToIsNull(1L))
                .thenReturn(Optional.of(openAssignment), Optional.empty());
        when(assetRepo.save(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));

        AssetResponse res = assetService.retireAsset(1L, adminEmail);

        assertEquals("RETIRED", res.getStatus());
        assertNull(res.getCurrentCustodianUserId(), "a retired asset must not still show a custodian");

        ArgumentCaptor<AssetAssignment> saved = ArgumentCaptor.forClass(AssetAssignment.class);
        verify(assignmentRepo).save(saved.capture());
        assertNotNull(saved.getValue().getEffectiveTo(), "the open assignment must be closed, not left dangling");
    }

    @Test
    void retireAsset_notCurrentlyAssigned_doesNotTouchAssignments() {
        Asset asset = assetWithStatus("AVAILABLE");
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset));
        when(assignmentRepo.findByAssetIdAndEffectiveToIsNull(1L)).thenReturn(Optional.empty());
        when(assetRepo.save(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));

        AssetResponse res = assetService.retireAsset(1L, adminEmail);

        assertEquals("RETIRED", res.getStatus());
        verify(assignmentRepo, never()).save(any());
    }

    // ── Permission gates (V242): system roles unchanged, custom roles follow their grants ──

    private final String actorEmail = "actor@test.com";
    private final UUID actorId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    private void actingAs(String roleCode) {
        Role role = roleCode.startsWith("CUSTOM_")
                ? Role.builder().id(100).code(roleCode).displayName(roleCode).build()
                : SeedMatrix.role(roleCode);
        User actor = User.builder().id(actorId).email(actorEmail).roles(Set.of(role)).build();
        lenient().when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
    }

    private void retirableAsset() {
        lenient().when(assetRepo.findById(1L)).thenReturn(Optional.of(assetWithStatus("AVAILABLE")));
        lenient().when(assetRepo.save(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void pendingRequest(boolean requesterReportsToActor) {
        lenient().when(requestRepo.findById(5L)).thenReturn(Optional.of(AssetRequest.builder()
                .id(5L).employeeUserId(requesterId).categoryId(1).status("PENDING").build()));
        lenient().when(historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(requesterId)).thenReturn(Optional.of(
                EmployeeManagerHistory.builder().employeeUserId(requesterId)
                        .managerUserId(requesterReportsToActor ? actorId : UUID.randomUUID()).build()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HR_ADMIN", "SUPER_ADMIN", "CUSTOM_ASSETS"})
    void inventoryGates_allowHrSuperAdminAndCustomRoleWithAssetsPermissions(String roleCode) {
        actingAs(roleCode);
        retirableAsset();

        assertDoesNotThrow(() -> assetService.listAllAssets(actorEmail));
        assertDoesNotThrow(() -> assetService.countAssets(actorEmail));
        assertDoesNotThrow(() -> assetService.hrTileSummary(actorEmail));
        assertDoesNotThrow(() -> assetService.availableByCategory(1, actorEmail));
        assertDoesNotThrow(() -> assetService.retireAsset(1L, actorEmail));
        // Past the CREATE gate: fails on the unknown category, not on authorization.
        CreateAssetRequest create = new CreateAssetRequest();
        create.setAssetTag("A-9");
        create.setCategoryId(99);
        assertThrows(IllegalArgumentException.class, () -> assetService.createAsset(create, actorEmail));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPLOYEE", "MANAGER", "CUSTOM_OTHER", "CUSTOM_TEAM_APPROVER"})
    void inventoryGates_denyEveryoneElse(String roleCode) {
        actingAs(roleCode);
        retirableAsset();

        assertThrows(AccessDeniedException.class, () -> assetService.listAllAssets(actorEmail));
        assertThrows(AccessDeniedException.class, () -> assetService.countAssets(actorEmail));
        assertThrows(AccessDeniedException.class, () -> assetService.hrTileSummary(actorEmail));
        assertThrows(AccessDeniedException.class, () -> assetService.availableByCategory(1, actorEmail));
        assertThrows(AccessDeniedException.class, () -> assetService.retireAsset(1L, actorEmail));
        assertThrows(AccessDeniedException.class, () -> assetService.createAsset(new CreateAssetRequest(), actorEmail));
        verify(assetRepo, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HR_ADMIN", "SUPER_ADMIN", "CUSTOM_ASSETS"})
    void approve_orgWideHolderCanDecideAnyonesRequest(String roleCode) {
        actingAs(roleCode);
        pendingRequest(false);

        assertEquals("APPROVED", assetService.approveRequest(5L, actorEmail).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "CUSTOM_TEAM_APPROVER"})
    void approve_teamHolderCanDecideOnlyADirectReportsRequest(String roleCode) {
        actingAs(roleCode);
        pendingRequest(true);
        assertEquals("REJECTED", assetService.rejectRequest(5L, "no", actorEmail).getStatus());

        pendingRequest(false);
        assertThrows(AccessDeniedException.class, () -> assetService.approveRequest(5L, actorEmail));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPLOYEE", "CUSTOM_OTHER"})
    void approve_deniedWithoutApprovePermissionEvenForADirectReport(String roleCode) {
        actingAs(roleCode);
        pendingRequest(true);

        assertThrows(AccessDeniedException.class, () -> assetService.approveRequest(5L, actorEmail));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HR_ADMIN", "SUPER_ADMIN", "CUSTOM_ASSETS"})
    void pendingQueue_orgWideHolderSeesPendingAndApprovedForEveryone(String roleCode) {
        actingAs(roleCode);
        when(requestRepo.findByStatusIn(List.of("PENDING", "APPROVED"))).thenReturn(List.of());

        assetService.listPendingForApprover(actorEmail);

        verify(requestRepo, never()).findByStatus(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "EMPLOYEE", "CUSTOM_TEAM_APPROVER"})
    void pendingQueue_everyoneElseSeesOnlyDirectReportsPending(String roleCode) {
        actingAs(roleCode);
        when(requestRepo.findByStatus("PENDING")).thenReturn(List.of(
                AssetRequest.builder().id(5L).employeeUserId(requesterId).categoryId(1).status("PENDING").build(),
                AssetRequest.builder().id(6L).employeeUserId(UUID.randomUUID()).categoryId(1).status("PENDING").build()));
        when(historyRepo.findCurrentDirectReportIds(actorId)).thenReturn(List.of(requesterId));

        assertEquals(List.of(5L), assetService.listPendingForApprover(actorEmail).stream().map(r -> r.getId()).toList());
        verify(requestRepo, never()).findByStatusIn(any());
    }
}

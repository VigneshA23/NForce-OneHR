package com.nforce.onehr.service;

import com.nforce.onehr.dto.asset.AssetResponse;
import com.nforce.onehr.entity.Asset;
import com.nforce.onehr.entity.AssetAssignment;
import com.nforce.onehr.entity.AssetCategory;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private AssetService assetService;

    private final String adminEmail = "admin@test.com";
    private User adminUser;
    private AssetCategory category;

    @BeforeEach
    void setUp() {
        adminUser = User.builder().id(UUID.randomUUID()).email(adminEmail)
                .roles(Set.of(Role.builder().id(1).code("HR_ADMIN").displayName("HR Admin").build()))
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

    // ── Delete: only assets that were never assigned ──

    @Test
    void deleteAsset_neverAssigned_deletesAndAudits() {
        Asset asset = assetWithStatus("AVAILABLE");
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset));
        when(assignmentRepo.existsByAssetId(1L)).thenReturn(false);

        assetService.deleteAsset(1L, adminEmail);

        verify(assetRepo).delete(asset);
        verify(auditService).log(eq(adminUser.getId()), eq("ASSET_DELETED"), eq(adminUser.getId()), any(), isNull());
    }

    @Test
    void deleteAsset_withAssignmentHistory_isRefusedAndNothingIsDeleted() {
        Asset asset = assetWithStatus("RETIRED");
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset));
        when(assignmentRepo.existsByAssetId(1L)).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> assetService.deleteAsset(1L, adminEmail));

        assertTrue(ex.getMessage().contains("Retire it instead"));
        verify(assetRepo, never()).delete(any(Asset.class));
        verify(auditService, never()).log(any(), eq("ASSET_DELETED"), any(), any(), any());
    }
}

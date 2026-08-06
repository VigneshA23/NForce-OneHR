package com.nforce.onehr.service;

import com.nforce.onehr.dto.asset.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssetService {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> MANAGER_AND_ADMIN = Set.of("MANAGER", "HR_ADMIN", "SUPER_ADMIN");

    private final AssetRepository assetRepo;
    private final AssetCategoryRepository categoryRepo;
    private final AssetAssignmentRepository assignmentRepo;
    private final AssetRequestRepository requestRepo;
    private final EmployeeManagerHistoryRepository historyRepo;
    private final UserRepository userRepo;
    private final EmployeeRepository employeeRepo;
    private final LocationRepository locationRepo;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final NotificationService notificationService;

    // ── Categories ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AssetCategoryResponse> listCategories() {
        return categoryRepo.findAll().stream()
                .map(c -> AssetCategoryResponse.builder().id(c.getId()).name(c.getName()).build())
                .collect(Collectors.toList());
    }

    // ── My Assignments (Employee) ─────────────────────────

    @Transactional(readOnly = true)
    public List<AssetAssignmentResponse> myAssignments(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        return assignmentRepo.findByEmployeeUserIdAndEffectiveToIsNull(actorId).stream()
                .map(a -> toAssignmentResponse(a, true))
                .collect(Collectors.toList());
    }

    @Transactional
    public AssetAssignmentResponse acknowledgeReceipt(Long assignmentId, String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        AssetAssignment assignment = assignmentRepo.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));
        if (!assignment.getEmployeeUserId().equals(actorId)) {
            throw new AccessDeniedException("You can only acknowledge your own assignments");
        }
        if (assignment.getAcknowledgedAt() != null) {
            throw new IllegalStateException("Already acknowledged");
        }
        assignment.setAcknowledgedAt(Instant.now());
        assignmentRepo.save(assignment);
        return toAssignmentResponse(assignment, true);
    }

    // ── Asset Requests ────────────────────────────────────

    @Transactional
    public AssetRequestResponse submitRequest(SubmitAssetRequestRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        AssetCategory category = categoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset category"));

        AssetRequest entity = AssetRequest.builder()
                .employeeUserId(actor.getId())
                .categoryId(req.getCategoryId())
                .reason(req.getReason().trim())
                .status("PENDING")
                .build();
        entity = requestRepo.save(entity);
        auditService.log(actor.getId(), "ASSET_REQUEST_SUBMITTED", actor.getId());
        // Notify manager
        String requesterName = employeeName(actor.getId());
        historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(actor.getId())
                .ifPresent(h -> notificationService.send(h.getManagerUserId(), "ASSET_REQUEST_SUBMITTED",
                        "Asset Request",
                        requesterName + " requested " + category.getName() + ". Reason: " + req.getReason().trim(),
                        "/approvals?type=ASSET_REQUEST"));
        return toRequestResponse(entity, category.getName());
    }

    @Transactional(readOnly = true)
    public List<AssetRequestResponse> myRequests(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        return requestRepo.findByEmployeeUserIdOrderByCreatedAtDesc(actorId).stream()
                .map(r -> toRequestResponse(r, categoryName(r.getCategoryId())))
                .collect(Collectors.toList());
    }

    // ── Approval Center: pending asset requests ───────────

    @Transactional(readOnly = true)
    public List<AssetRequestResponse> listPendingForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        if (isAdminRole(actor)) {
            // HR/Super Admin see PENDING (to approve) + APPROVED (ready to fulfill)
            List<AssetRequest> requests = requestRepo.findByStatusIn(List.of("PENDING", "APPROVED"));
            return requests.stream()
                    .map(r -> toRequestResponse(r, categoryName(r.getCategoryId())))
                    .collect(Collectors.toList());
        }
        // Manager: only own direct reports, only PENDING
        List<AssetRequest> pending = requestRepo.findByStatus("PENDING");
        List<UUID> reportIds = historyRepo.findCurrentDirectReportIds(actor.getId());
        return pending.stream()
                .filter(r -> reportIds.contains(r.getEmployeeUserId()))
                .map(r -> toRequestResponse(r, categoryName(r.getCategoryId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public AssetRequestResponse approveRequest(Long requestId, String actorEmail) {
        User actor = requireActor(actorEmail);
        AssetRequest req = requirePendingRequest(requestId);
        assertCanDecideRequest(actor, req.getEmployeeUserId());

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        req.setStatus("APPROVED");
        req.setManagerDecidedBy(actor.getId());
        req.setManagerDecidedAt(Instant.now());
        requestRepo.save(req);
        String after = auditSnapshot.toJson(Map.of("status", "APPROVED", "managerDecidedBy", actor.getId().toString()));
        auditService.log(actor.getId(), "ASSET_REQUEST_APPROVED", actor.getId(), before, after);
        notificationService.send(req.getEmployeeUserId(), "ASSET_REQUEST_APPROVED",
                "Asset Request Approved",
                "Your " + categoryName(req.getCategoryId()) + " request was approved. HR will fulfill it shortly.",
                "/assets-expenses");
        return toRequestResponse(req, categoryName(req.getCategoryId()));
    }

    @Transactional
    public AssetRequestResponse rejectRequest(Long requestId, String reason, String actorEmail) {
        User actor = requireActor(actorEmail);
        AssetRequest req = requirePendingRequest(requestId);
        assertCanDecideRequest(actor, req.getEmployeeUserId());

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        req.setStatus("REJECTED");
        req.setManagerDecidedBy(actor.getId());
        req.setManagerDecidedAt(Instant.now());
        req.setRejectionReason(reason != null ? reason.trim() : null);
        requestRepo.save(req);
        String after = auditSnapshot.toJson(Map.of(
                "status", "REJECTED", "rejectionReason", req.getRejectionReason() != null ? req.getRejectionReason() : ""));
        auditService.log(actor.getId(), "ASSET_REQUEST_REJECTED", actor.getId(), before, after);
        notificationService.send(req.getEmployeeUserId(), "ASSET_REQUEST_REJECTED",
                "Asset Request Rejected",
                "Your " + categoryName(req.getCategoryId()) + " request was rejected." + (reason != null && !reason.isBlank() ? " Reason: " + reason.trim() : ""),
                "/assets-expenses");
        return toRequestResponse(req, categoryName(req.getCategoryId()));
    }

    // ── HR: Asset Inventory Management ───────────────────

    @Transactional(readOnly = true)
    public List<AssetResponse> listAllAssets(String actorEmail) {
        requireAdmin(actorEmail);
        return assetRepo.findAll().stream().map(this::toAssetResponse).collect(Collectors.toList());
    }

    @Transactional
    public AssetResponse createAsset(CreateAssetRequest req, String actorEmail) {
        requireAdmin(actorEmail);
        if (assetRepo.existsByAssetTag(req.getAssetTag())) {
            throw new IllegalArgumentException("Asset tag already exists: " + req.getAssetTag());
        }
        AssetCategory category = categoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset category"));
        Location location = req.getLocationId() != null
                ? locationRepo.findById(req.getLocationId())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown location"))
                : null;

        Asset asset = Asset.builder()
                .assetTag(req.getAssetTag())
                .category(category)
                .brand(req.getBrand())
                .model(req.getModel())
                .serialNumber(req.getSerialNumber())
                .purchaseDate(req.getPurchaseDate())
                .purchaseCost(req.getPurchaseCost())
                .warrantyExpiry(req.getWarrantyExpiry())
                .condition(req.getCondition() != null ? req.getCondition() : "GOOD")
                .status("AVAILABLE")
                .location(location)
                .build();
        asset = assetRepo.save(asset);
        UUID actorId = requireActor(actorEmail).getId();
        auditService.log(actorId, "ASSET_CREATED", actorId);
        return toAssetResponse(asset);
    }

    @Transactional
    public AssetResponse assignAsset(Long assetId, AssignAssetRequest req, String actorEmail) {
        requireAdmin(actorEmail);
        User actor = requireActor(actorEmail);
        Asset asset = requireAsset(assetId);
        if (!"AVAILABLE".equals(asset.getStatus())) {
            throw new IllegalStateException("Asset is not available for assignment (status: " + asset.getStatus() + ")");
        }

        AssetAssignment assignment = AssetAssignment.builder()
                .assetId(assetId)
                .employeeUserId(req.getEmployeeUserId())
                .assignedBy(actor.getId())
                .effectiveFrom(Instant.now())
                .build();
        assignmentRepo.save(assignment);

        String before = auditSnapshot.toJson(Map.of("status", "AVAILABLE"));
        asset.setStatus("ASSIGNED");
        assetRepo.save(asset);
        String after = auditSnapshot.toJson(Map.of("status", "ASSIGNED", "assignedTo", req.getEmployeeUserId().toString()));
        auditService.log(actor.getId(), "ASSET_ASSIGNED", actor.getId(), before, after);
        notificationService.send(req.getEmployeeUserId(), "ASSET_ASSIGNED",
                "Asset Assigned",
                asset.getAssetTag() + " (" + asset.getCategory().getName() + ") has been assigned to you.",
                "/assets-expenses");
        return toAssetResponse(asset);
    }

    @Transactional
    public AssetResponse reassignAsset(Long assetId, AssignAssetRequest req, String actorEmail) {
        requireAdmin(actorEmail);
        User actor = requireActor(actorEmail);
        Asset asset = requireAsset(assetId);
        if (!"ASSIGNED".equals(asset.getStatus())) {
            throw new IllegalStateException("Asset is not currently assigned");
        }

        // Close current assignment (effective_to = now) — never update employee_user_id in place
        AssetAssignment current = assignmentRepo.findByAssetIdAndEffectiveToIsNull(assetId)
                .orElseThrow(() -> new IllegalStateException("No open assignment found for this asset"));
        String before = auditSnapshot.toJson(Map.of("assignedTo", current.getEmployeeUserId().toString()));
        current.setEffectiveTo(Instant.now());
        assignmentRepo.saveAndFlush(current); // flush UPDATE before INSERT to satisfy partial unique index

        // New assignment row for the new employee
        AssetAssignment next = AssetAssignment.builder()
                .assetId(assetId)
                .employeeUserId(req.getEmployeeUserId())
                .assignedBy(actor.getId())
                .effectiveFrom(Instant.now())
                .build();
        assignmentRepo.save(next);

        String after = auditSnapshot.toJson(Map.of("assignedTo", req.getEmployeeUserId().toString()));
        auditService.log(actor.getId(), "ASSET_REASSIGNED", actor.getId(), before, after);
        return toAssetResponse(asset);
    }

    @Transactional
    public AssetResponse markReturned(Long assetId, MarkReturnedRequest req, String actorEmail) {
        requireAdmin(actorEmail);
        User actor = requireActor(actorEmail);
        Asset asset = requireAsset(assetId);
        if (!"ASSIGNED".equals(asset.getStatus())) {
            throw new IllegalStateException("Asset is not currently assigned");
        }

        AssetAssignment current = assignmentRepo.findByAssetIdAndEffectiveToIsNull(assetId)
                .orElseThrow(() -> new IllegalStateException("No open assignment found"));
        String before = auditSnapshot.toJson(Map.of("status", "ASSIGNED", "assignedTo", current.getEmployeeUserId().toString()));
        current.setEffectiveTo(Instant.now());
        current.setReturnCondition(req.getReturnCondition());
        assignmentRepo.save(current);

        // If returned in DAMAGED condition, send to IN_REPAIR rather than AVAILABLE
        String newStatus = "DAMAGED".equalsIgnoreCase(req.getReturnCondition()) ? "IN_REPAIR" : "AVAILABLE";
        asset.setStatus(newStatus);
        asset.setCondition(req.getReturnCondition());
        assetRepo.save(asset);
        String after = auditSnapshot.toJson(Map.of("status", newStatus, "condition", req.getReturnCondition() != null ? req.getReturnCondition() : ""));
        auditService.log(actor.getId(), "ASSET_RETURNED", actor.getId(), before, after);
        return toAssetResponse(asset);
    }

    @Transactional
    public AssetResponse retireAsset(Long assetId, String actorEmail) {
        requireAdmin(actorEmail);
        User actor = requireActor(actorEmail);
        Asset asset = requireAsset(assetId);
        String before = auditSnapshot.toJson(Map.of("status", asset.getStatus()));
        asset.setStatus("RETIRED");
        assetRepo.save(asset);
        String after = auditSnapshot.toJson(Map.of("status", "RETIRED"));
        auditService.log(actor.getId(), "ASSET_RETIRED", actor.getId(), before, after);
        return toAssetResponse(asset);
    }

    // ── HR: Fulfill approved asset request ───────────────

    @Transactional
    public AssetRequestResponse fulfillRequest(Long requestId, FulfillAssetRequestRequest req, String actorEmail) {
        requireAdmin(actorEmail);
        User actor = requireActor(actorEmail);
        AssetRequest assetReq = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Asset request not found"));
        if (!"APPROVED".equals(assetReq.getStatus())) {
            throw new IllegalStateException("Only APPROVED requests can be fulfilled");
        }

        Asset asset = requireAsset(req.getAssetId());
        if (!"AVAILABLE".equals(asset.getStatus())) {
            throw new IllegalStateException("Selected asset is not available");
        }

        // Create assignment
        AssetAssignment assignment = AssetAssignment.builder()
                .assetId(asset.getId())
                .employeeUserId(assetReq.getEmployeeUserId())
                .assignedBy(actor.getId())
                .effectiveFrom(Instant.now())
                .build();
        assignmentRepo.save(assignment);

        asset.setStatus("ASSIGNED");
        assetRepo.save(asset);

        String before = auditSnapshot.toJson(Map.of("status", "APPROVED"));
        assetReq.setStatus("FULFILLED");
        assetReq.setFulfilledBy(actor.getId());
        assetReq.setFulfilledAt(Instant.now());
        requestRepo.save(assetReq);

        String after = auditSnapshot.toJson(Map.of("status", "FULFILLED", "assetTag", asset.getAssetTag()));
        auditService.log(actor.getId(), "ASSET_REQUEST_FULFILLED", actor.getId(), before, after);
        notificationService.send(assetReq.getEmployeeUserId(), "ASSET_REQUEST_FULFILLED",
                "Asset Request Fulfilled",
                "Your " + categoryName(assetReq.getCategoryId()) + " request has been fulfilled. " + asset.getAssetTag() + " is now assigned to you.",
                "/assets-expenses");
        return toRequestResponse(assetReq, categoryName(assetReq.getCategoryId()));
    }

    // ── Manager: Team view (read-only) ────────────────────

    @Transactional(readOnly = true)
    public List<AssetAssignmentResponse> teamAssignments(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<UUID> reportIds = historyRepo.findCurrentDirectReportIds(actor.getId());
        if (reportIds.isEmpty()) return List.of();
        return assignmentRepo.findByEmployeeUserIdInAndEffectiveToIsNull(reportIds).stream()
                .map(a -> toAssignmentResponse(a, true))
                .collect(Collectors.toList());
    }

    // ── Manager: all team asset requests (full lifecycle) ────

    @Transactional(readOnly = true)
    public List<AssetRequestResponse> teamRequests(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<UUID> reportIds = historyRepo.findCurrentDirectReportIds(actor.getId());
        if (reportIds.isEmpty()) return List.of();
        List<String> statuses = List.of("PENDING", "APPROVED", "FULFILLED", "REJECTED");
        return requestRepo.findByStatusInAndEmployeeUserIdIn(statuses, reportIds).stream()
                .map(r -> toRequestResponse(r, categoryName(r.getCategoryId())))
                .collect(Collectors.toList());
    }

    // ── Tile summary data ─────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> employeeTileSummary(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        List<AssetAssignment> current = assignmentRepo.findByEmployeeUserIdAndEffectiveToIsNull(actorId);
        List<String> categories = current.stream()
                .map(a -> {
                    Asset asset = assetRepo.findById(a.getAssetId()).orElse(null);
                    return asset != null ? asset.getCategory().getName() : "";
                })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        String subtitle;
        if (categories.isEmpty()) {
            subtitle = "None assigned";
        } else if (categories.size() <= 3) {
            subtitle = String.join(", ", categories);
        } else {
            subtitle = categories.subList(0, 3).stream().collect(Collectors.joining(", "))
                    + ", +" + (categories.size() - 3) + " more";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("assignedCount", current.size());
        result.put("assignedSubtitle", subtitle);
        result.put("openRequestCount",
                requestRepo.countByEmployeeUserIdAndStatus(actorId, "PENDING"));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> managerTileSummary(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<UUID> reportIds = historyRepo.findCurrentDirectReportIds(actor.getId());
        long assigned = reportIds.isEmpty() ? 0 :
                assignmentRepo.findByEmployeeUserIdInAndEffectiveToIsNull(reportIds).size();
        long overdue = reportIds.isEmpty() ? 0 :
                assignmentRepo.findOverdueAssignmentsForEmployees(reportIds).size();
        long pending = reportIds.isEmpty() ? 0 :
                requestRepo.findByStatusAndEmployeeUserIdIn("PENDING", reportIds).size();

        Map<String, Object> result = new HashMap<>();
        result.put("teamAssignedCount", assigned);
        result.put("teamMemberCount", reportIds.size());
        result.put("overdueReturnCount", overdue);
        result.put("pendingRequestCount", pending);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> hrTileSummary(String actorEmail) {
        requireAdmin(actorEmail);
        long totalAssigned = assignmentRepo.countByEffectiveToIsNull();
        long available = assetRepo.countByStatus("AVAILABLE");
        long overdue = assignmentRepo.findOverdueAssignments().size();
        long pendingFulfillment = requestRepo.findByStatus("APPROVED").size();

        Map<String, Object> result = new HashMap<>();
        result.put("totalAssigned", totalAssigned);
        result.put("available", available);
        result.put("overdueReturns", overdue);
        result.put("pendingFulfillment", pendingFulfillment);
        return result;
    }

    // ── Available assets for fulfillment ─────────────────

    @Transactional(readOnly = true)
    public List<AssetResponse> availableByCategory(Integer categoryId, String actorEmail) {
        requireAdmin(actorEmail);
        return assetRepo.findByCategoryIdAndStatus(categoryId, "AVAILABLE")
                .stream().map(this::toAssetResponse).collect(Collectors.toList());
    }

    // ── Guards ────────────────────────────────────────────

    private void requireAdmin(String actorEmail) {
        User actor = requireActor(actorEmail);
        if (!isAdminRole(actor)) {
            throw new AccessDeniedException("HR Admin or Super Admin role required");
        }
    }

    private void assertCanDecideRequest(User actor, UUID requestOwnerUserId) {
        if (isAdminRole(actor)) return;
        boolean isManager = actor.getRoles().stream()
                .anyMatch(r -> "MANAGER".equals(r.getCode()));
        if (isManager) {
            boolean isReport = historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(requestOwnerUserId)
                    .map(EmployeeManagerHistory::getManagerUserId)
                    .map(actor.getId()::equals)
                    .orElse(false);
            if (isReport) return;
        }
        throw new AccessDeniedException("Not authorized to decide on this request");
    }

    private boolean isAdminRole(User actor) {
        return actor.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
    }

    private AssetRequest requirePendingRequest(Long requestId) {
        AssetRequest req = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Asset request not found"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new IllegalStateException("Request is not in PENDING state");
        }
        return req;
    }

    private Asset requireAsset(Long assetId) {
        return assetRepo.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));
    }

    private User requireActor(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    // ── Mappers ───────────────────────────────────────────

    private AssetResponse toAssetResponse(Asset a) {
        AssetAssignment current = assignmentRepo.findByAssetIdAndEffectiveToIsNull(a.getId()).orElse(null);
        String custodianName = null;
        String custodianId = null;
        if (current != null) {
            custodianName = employeeName(current.getEmployeeUserId());
            custodianId = current.getEmployeeUserId().toString();
        }
        return AssetResponse.builder()
                .id(a.getId())
                .assetTag(a.getAssetTag())
                .categoryId(a.getCategory().getId())
                .categoryName(a.getCategory().getName())
                .brand(a.getBrand())
                .model(a.getModel())
                .serialNumber(a.getSerialNumber())
                .purchaseDate(a.getPurchaseDate())
                .purchaseCost(a.getPurchaseCost())
                .warrantyExpiry(a.getWarrantyExpiry())
                .condition(a.getCondition())
                .status(a.getStatus())
                .locationId(a.getLocation() != null ? a.getLocation().getId() : null)  // UUID
                .locationName(a.getLocation() != null ? a.getLocation().getName() : null)
                .currentCustodianName(custodianName)
                .currentCustodianUserId(custodianId)
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    private AssetAssignmentResponse toAssignmentResponse(AssetAssignment a, boolean includeAsset) {
        Asset asset = includeAsset ? assetRepo.findById(a.getAssetId()).orElse(null) : null;
        return AssetAssignmentResponse.builder()
                .id(a.getId())
                .assetId(a.getAssetId())
                .assetTag(asset != null ? asset.getAssetTag() : null)
                .categoryName(asset != null ? asset.getCategory().getName() : null)
                .brand(asset != null ? asset.getBrand() : null)
                .model(asset != null ? asset.getModel() : null)
                .condition(asset != null ? asset.getCondition() : null)
                .employeeUserId(a.getEmployeeUserId())
                .employeeName(employeeName(a.getEmployeeUserId()))
                .effectiveFrom(a.getEffectiveFrom())
                .effectiveTo(a.getEffectiveTo())
                .acknowledgedAt(a.getAcknowledgedAt())
                .returnCondition(a.getReturnCondition())
                .build();
    }

    private AssetRequestResponse toRequestResponse(AssetRequest r, String catName) {
        return AssetRequestResponse.builder()
                .id(r.getId())
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(employeeName(r.getEmployeeUserId()))
                .categoryId(r.getCategoryId())
                .categoryName(catName)
                .reason(r.getReason())
                .status(r.getStatus())
                .managerDecidedByName(r.getManagerDecidedBy() != null ? employeeName(r.getManagerDecidedBy()) : null)
                .managerDecidedAt(r.getManagerDecidedAt())
                .fulfilledByName(r.getFulfilledBy() != null ? employeeName(r.getFulfilledBy()) : null)
                .fulfilledAt(r.getFulfilledAt())
                .rejectionReason(r.getRejectionReason())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private String employeeName(UUID userId) {
        return employeeRepo.findById(userId).map(Employee::getFullName)
                .orElseGet(() -> userRepo.findById(userId).map(User::getEmail).orElse("Unknown"));
    }

    private String categoryName(Integer categoryId) {
        return categoryRepo.findById(categoryId).map(AssetCategory::getName).orElse("Unknown");
    }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.asset.*;
import com.nforce.onehr.service.AssetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    // ── Reference data ────────────────────────────────────

    @GetMapping("/categories")
    public List<AssetCategoryResponse> listCategories() {
        return assetService.listCategories();
    }

    // ── Employee: own assignments ─────────────────────────

    @GetMapping("/my-assignments")
    public List<AssetAssignmentResponse> myAssignments(Principal principal) {
        return assetService.myAssignments(principal.getName());
    }

    @PostMapping("/assignments/{id}/acknowledge")
    public AssetAssignmentResponse acknowledge(@PathVariable Long id, Principal principal) {
        return assetService.acknowledgeReceipt(id, principal.getName());
    }

    // ── Asset requests ────────────────────────────────────

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public AssetRequestResponse submitRequest(@Valid @RequestBody SubmitAssetRequestRequest req,
                                              Principal principal) {
        return assetService.submitRequest(req, principal.getName());
    }

    @GetMapping("/requests/mine")
    public List<AssetRequestResponse> myRequests(Principal principal) {
        return assetService.myRequests(principal.getName());
    }

    // ── Approval Center: pending requests ─────────────────
    // Decisions are made HERE — not on any other page.

    @GetMapping("/requests/pending")
    public List<AssetRequestResponse> pendingForApprover(Principal principal) {
        return assetService.listPendingForApprover(principal.getName());
    }

    @PostMapping("/requests/{id}/approve")
    public AssetRequestResponse approveRequest(@PathVariable Long id, Principal principal) {
        return assetService.approveRequest(id, principal.getName());
    }

    @PostMapping("/requests/{id}/reject")
    public AssetRequestResponse rejectRequest(@PathVariable Long id,
                                               @RequestBody DecisionRequest req,
                                               Principal principal) {
        return assetService.rejectRequest(id, req.getReason(), principal.getName());
    }

    // ── HR Admin: inventory management ───────────────────

    @GetMapping
    public List<AssetResponse> listAllAssets(Principal principal) {
        return assetService.listAllAssets(principal.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssetResponse createAsset(@Valid @RequestBody CreateAssetRequest req, Principal principal) {
        return assetService.createAsset(req, principal.getName());
    }

    @PostMapping("/{id}/assign")
    public AssetResponse assignAsset(@PathVariable Long id,
                                     @Valid @RequestBody AssignAssetRequest req,
                                     Principal principal) {
        return assetService.assignAsset(id, req, principal.getName());
    }

    @PostMapping("/{id}/reassign")
    public AssetResponse reassignAsset(@PathVariable Long id,
                                       @Valid @RequestBody AssignAssetRequest req,
                                       Principal principal) {
        return assetService.reassignAsset(id, req, principal.getName());
    }

    @PostMapping("/{id}/mark-returned")
    public AssetResponse markReturned(@PathVariable Long id,
                                      @Valid @RequestBody MarkReturnedRequest req,
                                      Principal principal) {
        return assetService.markReturned(id, req, principal.getName());
    }

    @PostMapping("/{id}/retire")
    public AssetResponse retireAsset(@PathVariable Long id, Principal principal) {
        return assetService.retireAsset(id, principal.getName());
    }

    @GetMapping("/available-by-category/{categoryId}")
    public List<AssetResponse> availableByCategory(@PathVariable Integer categoryId, Principal principal) {
        return assetService.availableByCategory(categoryId, principal.getName());
    }

    // ── HR Admin: fulfill approved requests ──────────────

    @PostMapping("/requests/{id}/fulfill")
    public AssetRequestResponse fulfillRequest(@PathVariable Long id,
                                               @Valid @RequestBody FulfillAssetRequestRequest req,
                                               Principal principal) {
        return assetService.fulfillRequest(id, req, principal.getName());
    }

    // ── Manager: team view ────────────────────────────────

    @GetMapping("/team-assignments")
    public List<AssetAssignmentResponse> teamAssignments(Principal principal) {
        return assetService.teamAssignments(principal.getName());
    }

    @GetMapping("/requests/team")
    public List<AssetRequestResponse> teamRequests(Principal principal) {
        return assetService.teamRequests(principal.getName());
    }

    // ── Tile summaries ────────────────────────────────────

    @GetMapping("/tiles/employee")
    public Map<String, Object> employeeTiles(Principal principal) {
        return assetService.employeeTileSummary(principal.getName());
    }

    @GetMapping("/tiles/manager")
    public Map<String, Object> managerTiles(Principal principal) {
        return assetService.managerTileSummary(principal.getName());
    }

    @GetMapping("/tiles/hr")
    public Map<String, Object> hrTiles(Principal principal) {
        return assetService.hrTileSummary(principal.getName());
    }
}

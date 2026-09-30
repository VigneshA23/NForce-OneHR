package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.asset.AssetAssignmentResponse;
import com.nforce.onehr.dto.asset.AssetRequestResponse;
import com.nforce.onehr.service.AssetService;
import com.nforce.onehr.service.AttendanceRulesService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Physical assets: the caller's own requests and assignments, approvals, and HR's inventory figures. */
public final class AssetDataProviders {

    private AssetDataProviders() {}

    /** The caller's own asset requests and where each one sits. */
    @Component
    @RequiredArgsConstructor
    public static class MyAssetRequests implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final AssetService assetService;

        @Override public String id() { return "asset.my-requests"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your recent asset requests"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("assets", "requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<AssetRequestResponse> requests = assetService.myRequests(context.getActorEmail());
            if (requests == null || requests.isEmpty()) return Optional.empty();

            return Optional.of(LiveDataText.cappedList(requests, MAX_ROWS, "asset request(s) raised by you", "most recent",
                    r -> "%s: %s".formatted(r.getCategoryName(), r.getStatus())));
        }
    }

    /**
     * Assets currently assigned to the caller - the Assets &amp; Expenses page's "My assets", through
     * the same {@code myAssignments} read. Returned items are counted but not listed.
     */
    @Component
    @RequiredArgsConstructor
    public static class MyAssetAssignments implements AssistantDataProvider {

        private static final int MAX_ROWS = 15;

        private final AssetService assetService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "asset.my-assignments"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Company assets assigned to you"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("assets", "my-assets"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<AssetAssignmentResponse> all = assetService.myAssignments(context.getActorEmail());
            List<AssetAssignmentResponse> current = all == null ? List.of()
                    : all.stream().filter(a -> a.getEffectiveTo() == null).toList();
            if (current.isEmpty()) return Optional.of("No company assets are currently assigned to you.");

            ZoneId zone = attendanceRulesService.getDefaultZoneId();
            String text = LiveDataText.cappedList(current, MAX_ROWS, "asset(s) currently assigned to you", "first", a ->
                    "%s: %s%s, assigned since %s, condition %s, %s".formatted(a.getAssetTag(), a.getCategoryName(),
                            describeModel(a), LiveDataText.date(a.getEffectiveFrom(), zone),
                            a.getCondition() == null ? "not recorded" : a.getCondition(),
                            a.getAcknowledgedAt() == null ? "not yet acknowledged by you" : "acknowledged"));
            long returned = all.size() - current.size();
            return Optional.of(returned > 0 ? text + "\nPreviously assigned and since returned: " + returned : text);
        }

        private static String describeModel(AssetAssignmentResponse a) {
            String model = ((a.getBrand() == null ? "" : a.getBrand()) + " " + (a.getModel() == null ? "" : a.getModel())).strip();
            return model.isEmpty() ? "" : " (" + model + ")";
        }
    }

    /**
     * The asset inventory in figures - the HR Assets tiles and the Super Admin dashboard's asset
     * tile. Both reads re-check that the caller is an HR Admin or Super Admin themselves.
     */
    @Component
    @RequiredArgsConstructor
    public static class OrgAssetSummary implements AssistantDataProvider {

        private final AssetService assetService;

        @Override public String id() { return "org-assets.summary"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "Company asset inventory"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.HR, AudienceBucket.ADMIN); }
        @Override public Set<String> modules() { return Set.of("assets-admin", "assets"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            String email = context.getActorEmail();
            long total = assetService.countAssets(email);
            Map<String, Object> tiles = assetService.hrTileSummary(email);
            return Optional.of(("Asset inventory: %d asset(s) in total; %s currently assigned; %s available; %s overdue for return; "
                    + "%s approved request(s) awaiting fulfilment.").formatted(total,
                    tiles.getOrDefault("totalAssigned", 0), tiles.getOrDefault("available", 0),
                    tiles.getOrDefault("overdueReturns", 0), tiles.getOrDefault("pendingFulfillment", 0)));
        }
    }

    /**
     * Asset requests waiting on the caller's decision.
     *
     * <p>{@code AssetService.listPendingForApprover} returns {@code PENDING} items for a Manager
     * but {@code PENDING} and {@code APPROVED} for HR/Admin — the latter also covers items already
     * approved and awaiting fulfilment, which is a different question than "awaiting your decision".
     * Filtered to {@code PENDING} here to match that phrase, the same way
     * {@code ApprovalCenterController} narrows its own admin branch for this queue.
     */
    @Component
    @RequiredArgsConstructor
    public static class PendingAssetRequests implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;
        private static final String STATUS_PENDING = "PENDING";

        private final AssetService assetService;

        @Override public String id() { return "asset.pending-approvals"; }
        @Override public DataScope scope() { return DataScope.APPROVALS; }
        @Override public String title() { return "Asset requests waiting for your decision"; }

        @Override
        public Set<AudienceBucket> audiences() {
            return Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
        }

        @Override public Set<String> modules() { return Set.of("assets", "approvals"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<AssetRequestResponse> pending = assetService.listPendingForApprover(context.getActorEmail()).stream()
                    .filter(r -> STATUS_PENDING.equals(r.getStatus()))
                    .toList();
            if (pending.isEmpty()) return Optional.empty();

            String rows = pending.stream()
                    .limit(MAX_ROWS)
                    .map(r -> "- %s: %s".formatted(r.getEmployeeName(), r.getCategoryName()))
                    .collect(Collectors.joining("\n"));

            return Optional.of("%d awaiting your decision.\n%s".formatted(pending.size(), rows));
        }
    }
}

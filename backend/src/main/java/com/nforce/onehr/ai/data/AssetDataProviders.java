package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.asset.AssetRequestResponse;
import com.nforce.onehr.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** The caller's own physical asset requests, both sides — mirrors {@code LeaveDataProviders}. */
public final class AssetDataProviders {

    private AssetDataProviders() {}

    /** The caller's own asset requests and where each one sits. */
    @Component
    @RequiredArgsConstructor
    public static class MyAssetRequests implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final AssetService assetService;

        @Override public String id() { return "asset.my-requests"; }
        @Override public String title() { return "Your recent asset requests"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("assets", "requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<AssetRequestResponse> requests = assetService.myRequests(context.getActorEmail());
            if (requests == null || requests.isEmpty()) return Optional.empty();

            return Optional.of(requests.stream()
                    .limit(MAX_ROWS)
                    .map(r -> "- %s: %s".formatted(r.getCategoryName(), r.getStatus()))
                    .collect(Collectors.joining("\n")));
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

package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.service.RegularizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The caller's own attendance regularization requests — both sides, mirroring
 * {@code LeaveDataProviders}: what the caller has submitted, and what is waiting on their decision.
 *
 * <p>Regularization is a genuine two-stage workflow (Manager, then HR Admin), unlike Leave/Expense's
 * single decision. {@code RegularizationService.listPendingForApprover(actorEmail)} already resolves
 * which stage's queue the caller should see - PENDING items assigned to a Manager, or
 * PARTIALLY_APPROVED items company-wide for HR Admin - so this provider adds no logic of its own; it
 * reports whatever that method returns.
 */
public final class RegularizationDataProviders {

    private RegularizationDataProviders() {}

    /** The caller's own regularization requests and where each one sits. */
    @Component
    @RequiredArgsConstructor
    public static class MyRegularizations implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final RegularizationService regularizationService;

        @Override public String id() { return "regularization.my-requests"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your recent regularization requests"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance", "requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<RegularizationResponse> requests = regularizationService.listMine(context.getActorEmail());
            if (requests == null || requests.isEmpty()) return Optional.empty();

            return Optional.of(LiveDataText.cappedList(requests, MAX_ROWS, "regularization request(s) raised by you", "most recent",
                    r -> "%s: %s".formatted(r.getAttendanceDate(), r.getStatus())));
        }
    }

    /** Regularization requests waiting on the caller to decide, at whichever stage applies to them. */
    @Component
    @RequiredArgsConstructor
    public static class PendingRegularizations implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final RegularizationService regularizationService;

        @Override public String id() { return "regularization.pending-approvals"; }
        @Override public DataScope scope() { return DataScope.APPROVALS; }
        @Override public String title() { return "Regularization requests waiting for your decision"; }

        @Override
        public Set<AudienceBucket> audiences() {
            return Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
        }

        @Override public Set<String> modules() { return Set.of("attendance", "approvals"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<RegularizationResponse> pending = regularizationService.listPendingForApprover(context.getActorEmail());
            if (pending == null || pending.isEmpty()) return Optional.empty();

            String rows = pending.stream()
                    .limit(MAX_ROWS)
                    .map(r -> "- %s: %s (%s)".formatted(r.getEmployeeName(), r.getAttendanceDate(), r.getStatus()))
                    .collect(Collectors.joining("\n"));

            return Optional.of("%d awaiting your decision.\n%s".formatted(pending.size(), rows));
        }
    }
}

package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.attendance.OvertimeRequestResponse;
import com.nforce.onehr.service.OvertimeRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** The caller's own overtime requests, both sides — mirrors {@code LeaveDataProviders} exactly. */
public final class OvertimeDataProviders {

    private OvertimeDataProviders() {}

    /** The caller's own overtime requests and where each one sits. */
    @Component
    @RequiredArgsConstructor
    public static class MyOvertime implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final OvertimeRequestService overtimeRequestService;

        @Override public String id() { return "overtime.my-requests"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your recent overtime requests"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance", "requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<OvertimeRequestResponse> requests = overtimeRequestService.listMine(context.getActorEmail());
            if (requests == null || requests.isEmpty()) return Optional.empty();

            return Optional.of(LiveDataText.cappedList(requests, MAX_ROWS, "overtime request(s) raised by you", "most recent",
                    r -> "%s: %s".formatted(r.getWorkDate(), r.getStatus())));
        }
    }

    /** Overtime requests waiting on the caller's decision. */
    @Component
    @RequiredArgsConstructor
    public static class PendingOvertime implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final OvertimeRequestService overtimeRequestService;

        @Override public String id() { return "overtime.pending-approvals"; }
        @Override public DataScope scope() { return DataScope.APPROVALS; }
        @Override public String title() { return "Overtime requests waiting for your decision"; }

        @Override
        public Set<AudienceBucket> audiences() {
            return Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
        }

        @Override public Set<String> modules() { return Set.of("attendance", "approvals"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<OvertimeRequestResponse> pending = overtimeRequestService.listPendingForApprover(context.getActorEmail());
            if (pending == null || pending.isEmpty()) return Optional.empty();

            String rows = pending.stream()
                    .limit(MAX_ROWS)
                    .map(r -> "- %s: %s".formatted(r.getEmployeeName(), r.getWorkDate()))
                    .collect(Collectors.joining("\n"));

            return Optional.of("%d awaiting your decision.\n%s".formatted(pending.size(), rows));
        }
    }
}

package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.service.AttendanceRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The caller's own Work From Home and Partial Day requests.
 *
 * <p>One provider pair rather than two, because the underlying model is one - a single
 * {@code AttendanceRequestService} and {@code AttendanceRequestResponse}, distinguished only by a
 * {@code requestType} field ({@code "WFH"} or {@code "PARTIAL_DAY"}). {@code listPendingForApprover}
 * and {@code listMine} both return the two types mixed together; each request line states its own
 * type, so nothing is lost by not splitting them into separate providers.
 */
public final class AttendanceRequestDataProviders {

    private AttendanceRequestDataProviders() {}

    private static String typeLabel(String requestType) {
        return "WFH".equals(requestType) ? "Work From Home" : "Partial Day";
    }

    /** The caller's own WFH and Partial Day requests and where each one sits. */
    @Component
    @RequiredArgsConstructor
    public static class MyWfhAndPartialDay implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final AttendanceRequestService attendanceRequestService;

        @Override public String id() { return "attendance-request.my-requests"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your recent Work From Home and Partial Day requests"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance", "requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<AttendanceRequestResponse> requests = attendanceRequestService.listMine(context.getActorEmail());
            if (requests == null || requests.isEmpty()) return Optional.empty();

            return Optional.of(LiveDataText.cappedList(requests, MAX_ROWS, "Work From Home / Partial Day request(s) raised by you",
                    "most recent", r -> "%s, %s: %s".formatted(typeLabel(r.getRequestType()), r.getRequestDate(), r.getStatus())));
        }
    }

    /** WFH and Partial Day requests waiting on the caller's decision. */
    @Component
    @RequiredArgsConstructor
    public static class PendingWfhAndPartialDay implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final AttendanceRequestService attendanceRequestService;

        @Override public String id() { return "attendance-request.pending-approvals"; }
        @Override public DataScope scope() { return DataScope.APPROVALS; }
        @Override public String title() { return "Work From Home and Partial Day requests waiting for your decision"; }

        @Override
        public Set<AudienceBucket> audiences() {
            return Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
        }

        @Override public Set<String> modules() { return Set.of("attendance", "approvals"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<AttendanceRequestResponse> pending = attendanceRequestService.listPendingForApprover(context.getActorEmail());
            if (pending == null || pending.isEmpty()) return Optional.empty();

            String rows = pending.stream()
                    .limit(MAX_ROWS)
                    .map(r -> "- %s: %s, %s".formatted(r.getEmployeeName(), typeLabel(r.getRequestType()), r.getRequestDate()))
                    .collect(Collectors.joining("\n"));

            return Optional.of("%d awaiting your decision.\n%s".formatted(pending.size(), rows));
        }
    }
}

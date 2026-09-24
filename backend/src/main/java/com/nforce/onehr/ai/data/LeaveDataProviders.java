package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.service.LeaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The caller's own leave figures.
 *
 * <p>All three providers here call {@code LeaveService} methods that take {@code actorEmail} and do
 * their own scoping, so none of them contains a line of authorisation logic. That is the point:
 * the assistant sees exactly what the Leave page would show this person, through the same code.
 */
public final class LeaveDataProviders {

    private LeaveDataProviders() {}

    /** Balance per leave type. The single most-asked question this feature exists to answer. */
    @Component
    @RequiredArgsConstructor
    public static class Balances implements AssistantDataProvider {

        private final LeaveService leaveService;

        @Override public String id() { return "leave.balances"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your current leave balances"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("leave"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<LeaveBalanceResponse> balances = leaveService.listMyBalances(context.getActorEmail());
            if (balances == null || balances.isEmpty()) return Optional.empty();

            // Annual, Sick and Casual share one consolidated balance (V122), so the numbers can
            // legitimately look identical across those types. Listing them per type anyway, because
            // collapsing them here would hide a real property of the system from the model.
            return Optional.of(balances.stream()
                    .map(b -> "- %s: %s of %s days remaining (%s used)".formatted(
                            b.getLeaveTypeName(), b.getRemainingDays(), b.getTotalDays(), b.getUsedDays()))
                    .collect(Collectors.joining("\n")));
        }
    }

    /** The caller's own leave requests and where each one currently sits. */
    @Component
    @RequiredArgsConstructor
    public static class MyRequests implements AssistantDataProvider {

        /** Enough to answer "where is my request", without tipping a year of history into a prompt. */
        private static final int MAX_ROWS = 5;

        private final LeaveService leaveService;

        @Override public String id() { return "leave.my-requests"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your recent leave requests"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("leave", "requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<LeaveRequestResponse> requests = leaveService.listMyRequests(context.getActorEmail());
            if (requests == null || requests.isEmpty()) return Optional.empty();

            // Status matters, and so does who decided, because "who do I chase" is the real question
            // behind "where is my request". The reason the employee typed is deliberately left out -
            // it is the most personal field on the row and adds nothing to an answer about status.
            return Optional.of(LiveDataText.cappedList(requests, MAX_ROWS, "leave request(s) raised by you", "most recent",
                    r -> "%s, %s to %s (%s days): %s%s".formatted(
                            r.getLeaveTypeName(), r.getStartDate(), r.getEndDate(), r.getTotalDays(), r.getStatus(),
                            r.getDecidedByName() == null ? "" : " (decided by " + r.getDecidedByName() + ")")));
        }
    }

    /** Leave waiting on the caller to decide. Manager and HR only — meaningless for an Employee. */
    @Component
    @RequiredArgsConstructor
    public static class PendingApprovals implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final LeaveService leaveService;

        @Override public String id() { return "leave.pending-approvals"; }
        @Override public DataScope scope() { return DataScope.APPROVALS; }
        @Override public String title() { return "Leave requests waiting for your decision"; }

        @Override
        public Set<AudienceBucket> audiences() {
            return Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
        }

        @Override public Set<String> modules() { return Set.of("leave", "approvals"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<LeaveRequestResponse> pending = leaveService.listPendingApprovals(context.getActorEmail());
            if (pending == null || pending.isEmpty()) return Optional.empty();

            String rows = pending.stream()
                    .limit(MAX_ROWS)
                    .map(r -> "- %s: %s, %s to %s (%s days)".formatted(
                            r.getEmployeeName(), r.getLeaveTypeName(),
                            r.getStartDate(), r.getEndDate(), r.getTotalDays()))
                    .collect(Collectors.joining("\n"));

            // The count is stated separately because it is what the person actually asked for, and
            // the rows are capped.
            return Optional.of("%d awaiting your decision.\n%s".formatted(pending.size(), rows));
        }
    }
}

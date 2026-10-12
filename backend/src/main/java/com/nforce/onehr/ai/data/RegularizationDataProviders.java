package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.RegularizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    private static String dayOf(LocalDateTime createdAt) {
        return createdAt == null ? "-" : createdAt.toLocalDate().toString();
    }

    /** The caller's own regularization requests and where each one sits. */
    @Component
    @RequiredArgsConstructor
    public static class MyRegularizations implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final RegularizationService regularizationService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "regularization.my-requests"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your regularization requests"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance", "requests", "regularization"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<RegularizationResponse> requests = regularizationService.listMine(context.getActorEmail());
            // Stated, not omitted: a missing block reads as "not looked up", not "none raised".
            if (requests == null || requests.isEmpty()) return Optional.of("You have never raised a regularization request.");

            // "How many did I raise this month and how many were approved" needs every request this
            // month counted by status - five recent rows cannot answer it (ONEHR).
            LocalDate monthStart = LocalDate.now(attendanceRulesService.getDefaultZoneId()).withDayOfMonth(1);
            List<RegularizationResponse> thisMonth = requests.stream()
                    .filter(r -> r.getCreatedAt() != null && !r.getCreatedAt().toLocalDate().isBefore(monthStart))
                    .toList();
            String month = "Raised this month (submitted on or after %s): %d%s".formatted(monthStart, thisMonth.size(),
                    thisMonth.isEmpty() ? "" : " - by status: " + OrganisationDataProviders.groupCounts(thisMonth, RegularizationResponse::getStatus));

            return Optional.of(month + "\n" + LiveDataText.cappedList(requests, MAX_ROWS, "regularization request(s) raised by you, ever",
                    "most recent", r -> "%s: %s, submitted %s".formatted(r.getAttendanceDate(), r.getStatus(), dayOf(r.getCreatedAt()))));
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

        @Override public Set<String> modules() { return Set.of("attendance", "approvals", "regularization-approvals"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<RegularizationResponse> pending = regularizationService.listPendingForApprover(context.getActorEmail());
            if (pending == null || pending.isEmpty()) return Optional.of("0 regularization requests awaiting your decision.");

            List<RegularizationResponse> oldestFirst = pending.stream()
                    .sorted(Comparator.comparing(RegularizationResponse::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            return Optional.of(LiveDataText.cappedList(oldestFirst, MAX_ROWS, "regularization request(s) awaiting your decision",
                    "oldest", r -> "%s: regularization date %s, requested check-in %s, check-out %s, submitted %s, status %s, reason: %s".formatted(
                            r.getEmployeeName(), r.getAttendanceDate(), LiveDataText.clock(r.getRequestedCheckIn()),
                            LiveDataText.clock(r.getRequestedCheckOut()), dayOf(r.getCreatedAt()), r.getStatus(),
                            r.getReason() == null ? "-" : r.getReason())));
        }
    }
}

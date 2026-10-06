package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.LeaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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

    /**
     * Balance per leave type, and what the used days are made of. The single most-asked question
     * this feature exists to answer.
     *
     * <p>"Used" is not only leave taken: a PAID_LEAVE attendance penalty is paid by adding to the
     * same {@code usedDays} ({@code PenaltyDeductionService}), and a reversal gives it back. Shown
     * "1 used" alone, the model told an employee with no leave request at all that they had taken a
     * day of Annual Leave (ONEHR - leave balance decrease blamed on leave instead of a penalty). So
     * the still-active penalty deductions and this year's approved leave are both stated here.
     */
    @Component
    @RequiredArgsConstructor
    public static class Balances implements AssistantDataProvider {

        private final LeaveService leaveService;
        private final EmployeeRepository employeeRepository;
        private final AttendancePenaltyRepository attendancePenaltyRepository;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "leave.balances"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your current leave balances, and what reduced them"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("leave", "leave-balances"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<LeaveBalanceResponse> balances = leaveService.listMyBalances(context.getActorEmail());
            if (balances == null || balances.isEmpty()) return Optional.empty();

            // Annual, Sick and Casual share one consolidated balance (V122), so the numbers can
            // legitimately look identical across those types. Listing them per type anyway, because
            // collapsing them here would hide a real property of the system from the model.
            StringBuilder out = new StringBuilder("Leave types available to you - exactly %d, each with its balance. "
                    .formatted(balances.size()) + "These are the leave types you can apply for:\n");
            out.append(balances.stream()
                    .map(b -> "- %s: %s of %s days remaining (%s used)".formatted(
                            b.getLeaveTypeName(), b.getRemainingDays(), b.getTotalDays(), b.getUsedDays()))
                    .collect(Collectors.joining("\n")));

            int year = LocalDate.now(attendanceRulesService.getDefaultZoneId()).getYear();
            LocalDate from = LocalDate.of(year, 1, 1), to = LocalDate.of(year, 12, 31);
            // Active (not cancelled or reversed) only: a reversal already returned its days.
            List<AttendancePenalty> deducted = employeeRepository.findByUser_Email(context.getActorEmail())
                    .map(e -> attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                            e.getUserId(), from, to, AttendancePenaltyStatus.PENDING_REVIEW))
                    .orElse(List.of()).stream()
                    .filter(p -> p.getLeaveDeductionDays() != null && p.getLeaveDeductionDays().signum() > 0)
                    .sorted(Comparator.comparing(AttendancePenalty::getIncidentDate).reversed())
                    .toList();
            BigDecimal penaltyDays = deducted.stream().map(AttendancePenalty::getLeaveDeductionDays).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.append("\n\nUsed days above include attendance penalty deductions as well as approved leave. A penalty "
                    + "deduction is not leave taken.");
            out.append(deducted.isEmpty()
                    ? "\nAttendance penalties deducted from your leave balance in %d: none.".formatted(year)
                    : "\nAttendance penalties deducted from your leave balance in %d (active) - exactly %d, %s day(s) in total:"
                            .formatted(year, deducted.size(), penaltyDays.stripTrailingZeros().toPlainString()));
            for (int i = 0; i < deducted.size(); i++) {
                AttendancePenalty p = deducted.get(i);
                out.append("\n%d. %s: %s penalty - %s day(s) deducted from leave%s%s".formatted(i + 1, p.getIncidentDate(),
                        p.getDiscrepancyType(), p.getLeaveDeductionDays().stripTrailingZeros().toPlainString(),
                        p.getLeaveBreakdown() == null ? "" : " (" + p.getLeaveBreakdown().replaceAll("[{}\"]", "").replace(":", " ") + ")",
                        p.getLopDays() == null || p.getLopDays().signum() <= 0 ? ""
                                : ", plus %s day(s) loss of pay".formatted(p.getLopDays().stripTrailingZeros().toPlainString())));
            }

            List<LeaveRequestResponse> approved = Objects.requireNonNullElse(leaveService.listMyRequests(context.getActorEmail()),
                    List.<LeaveRequestResponse>of()).stream()
                    .filter(r -> "APPROVED".equals(r.getStatus()) && r.getStartDate() != null && r.getStartDate().getYear() == year)
                    .toList();
            BigDecimal leaveDays = approved.stream().map(r -> r.getTotalDays() == null ? BigDecimal.ZERO : r.getTotalDays())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.append(approved.isEmpty()
                    ? "\nApproved leave requests in %d: none - no used days come from leave taken.".formatted(year)
                    : "\nApproved leave requests in %d: exactly %d, %s day(s) in total.".formatted(year, approved.size(),
                            leaveDays.stripTrailingZeros().toPlainString()));
            return Optional.of(out.toString());
        }
    }

    /**
     * The caller's own leave requests and where each one currently sits, narrowed to the period and
     * status the question names ("my rejected leave requests for 21-09-2026 to 28-09-2026").
     *
     * <p>Always states the complete count of what matched, "none" included, because every request
     * the caller has ever raised is checked: "why was my leave on the 3rd rejected" when there was
     * no request on the 3rd must be answerable as "there was no such request", not deflected to
     * Attendance (ONEHR). Previously the five most recent rows only, unfiltered - a question about
     * any other request got directions to My Requests instead of the rows.
     */
    @Component
    @RequiredArgsConstructor
    public static class MyRequests implements AssistantDataProvider {

        /** Enough for any one period's requests, without tipping a year of history into a prompt. */
        private static final int MAX_ROWS = 15;

        private static final Map<String, Pattern> STATUS_WORDS = Map.of(
                "REJECTED", Pattern.compile("\\b(reject\\w*|declin\\w*|denied)\\b", Pattern.CASE_INSENSITIVE),
                "APPROVED", Pattern.compile("\\bapprov(ed|al)\\b", Pattern.CASE_INSENSITIVE),
                "PENDING", Pattern.compile("\\bpending\\b", Pattern.CASE_INSENSITIVE),
                "CANCELLED", Pattern.compile("\\bcancel+(ed|led)?\\b", Pattern.CASE_INSENSITIVE),
                "WITHDRAWN", Pattern.compile("\\bwithdrawn?\\b", Pattern.CASE_INSENSITIVE));

        private final LeaveService leaveService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "leave.my-requests"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your leave requests"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("leave", "requests", "leave-requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            return fetch(context, null);
        }

        @Override
        public Optional<String> fetch(AssistantRequestContext context, String question) {
            List<LeaveRequestResponse> requests = leaveService.listMyRequests(context.getActorEmail());
            if (requests == null || requests.isEmpty()) {
                return Optional.of("You have never raised a leave request: none exist on any date, so none was approved or rejected.");
            }

            Optional<MyTeamDateRange.Range> period = MyTeamDateRange.named(question,
                    LocalDate.now(attendanceRulesService.getDefaultZoneId()));
            Set<String> statuses = question == null ? Set.of() : STATUS_WORDS.entrySet().stream()
                    .filter(e -> e.getValue().matcher(question).find())
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            List<LeaveRequestResponse> matching = requests.stream()
                    .filter(r -> period.isEmpty() || (!r.getEndDate().isBefore(period.get().from())
                            && !r.getStartDate().isAfter(period.get().to())))
                    .filter(r -> statuses.isEmpty() || statuses.contains(r.getStatus()))
                    .toList();

            StringBuilder out = new StringBuilder("All %d leave request(s) you have ever raised, by status: %s."
                    .formatted(requests.size(), OrganisationDataProviders.groupCounts(requests, LeaveRequestResponse::getStatus)));
            String filter = (statuses.isEmpty() ? "" : "status " + String.join("/", statuses))
                    + (statuses.isEmpty() || period.isEmpty() ? "" : ", ")
                    + period.map(p -> "overlapping " + p.label()).orElse("");
            out.append(filter.isEmpty() ? "\n" : "\nMatching what the question asks for (%s): ".formatted(filter));
            if (matching.isEmpty()) {
                return Optional.of(out.append("none - every request was checked, and no leave request of yours matches, "
                        + "so there is no such request, approval or rejection to report.").toString());
            }
            // Status matters, and so does who decided and why, because "who do I chase" and "why was
            // it rejected" are the real questions. The reason the employee typed is deliberately left
            // out - it is the most personal field on the row and adds nothing to an answer about status.
            return Optional.of(out.append(LiveDataText.cappedList(matching, MAX_ROWS, "leave request(s)", "most recent",
                    r -> "%s, %s to %s (%s days): %s%s%s".formatted(
                            r.getLeaveTypeName(), r.getStartDate(), r.getEndDate(), r.getTotalDays(), r.getStatus(),
                            r.getDecidedByName() == null ? "" : " (decided by " + r.getDecidedByName() + ")",
                            r.getDecisionReason() == null || r.getDecisionReason().isBlank() ? ""
                                    : ", reason given: " + r.getDecisionReason().strip()))).toString());
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

            return Optional.of(LiveDataText.cappedList(pending, MAX_ROWS, "leave request(s) awaiting your decision", "first",
                    r -> "%s: %s, %s to %s (%s days)".formatted(
                            r.getEmployeeName(), r.getLeaveTypeName(),
                            r.getStartDate(), r.getEndDate(), r.getTotalDays())));
        }
    }
}

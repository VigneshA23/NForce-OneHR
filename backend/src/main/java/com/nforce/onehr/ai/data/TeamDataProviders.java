package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.ManagerDashboardDto;
import com.nforce.onehr.dto.asset.AssetAssignmentResponse;
import com.nforce.onehr.dto.asset.AssetRequestResponse;
import com.nforce.onehr.dto.attendance.AttendancePenaltyResponse;
import com.nforce.onehr.dto.expense.ExpenseClaimResponse;
import com.nforce.onehr.service.AssetService;
import com.nforce.onehr.service.AttendancePenaltyService;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import com.nforce.onehr.service.EmployeeService;
import com.nforce.onehr.service.ExpenseService;
import com.nforce.onehr.service.LeaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Everything about a caller's own direct reports that they can already see manually - who they are,
 * their attendance, leave, penalties, expense claims and assets - exactly as the Manager Home, My
 * Team, Team Attendance, Team Leave and the Assets &amp; Expenses "Team" tab show them.
 *
 * <p>Every read here resolves the team from the actor's own reporting line (current direct reports
 * only, via {@code EmployeeManagerHistory}), never from a role, so nothing in this class decides who
 * is on whose team. That is also why the audience is Manager, HR Admin <em>and</em> Super Admin: an
 * HR Admin or Super Admin can be somebody's reporting manager too, exactly like a plain Manager, and
 * a "who are my direct reports" question deserves their real reports here, not "everyone" - that
 * organisation-wide reading is a separate question {@code OrganisationDataProviders} answers.
 */
public final class TeamDataProviders {

    private TeamDataProviders() {}

    private static final int MAX_NAMES = 25;

    /** Manager, HR Admin and Super Admin: whoever has direct reports at all. Never Employee. */
    private static final Set<AudienceBucket> TEAM_AUDIENCES =
            Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);

    /** Direct reports and recent team joiners - the Manager Home "My team" card. */
    @Component
    @RequiredArgsConstructor
    public static class TeamMembers implements AssistantDataProvider {

        private static final int JOINER_WINDOW_DAYS = 90;

        private final EmployeeService employeeService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "team.members"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports"; }
        @Override public Set<AudienceBucket> audiences() { return TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("team", "people"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            ManagerDashboardDto team = employeeService.getManagerDashboard(context.getActorEmail());
            List<ManagerDashboardDto.DirectReport> reports = team == null || team.getDirectReports() == null
                    ? List.of() : team.getDirectReports();
            if (reports.isEmpty()) return Optional.of("You have no direct reports right now.");

            long active = reports.stream().filter(ManagerDashboardDto.DirectReport::isActive).count();
            StringBuilder out = new StringBuilder("Exactly %d direct report(s): %d active, %d inactive."
                    .formatted(reports.size(), active, reports.size() - active));
            reports.stream()
                    .sorted(Comparator.comparing(ManagerDashboardDto.DirectReport::getFullName, String.CASE_INSENSITIVE_ORDER))
                    .limit(MAX_NAMES)
                    .forEach(r -> out.append("\n- %s (%s), %s, %s%s".formatted(r.getFullName(), r.getEmployeeCode(),
                            orNotSet(r.getDesignationName()), orNotSet(r.getDepartmentName()), r.isActive() ? "" : ", inactive")));
            if (reports.size() > MAX_NAMES) out.append("\n(and %d more)".formatted(reports.size() - MAX_NAMES));

            LocalDate since = LocalDate.now(attendanceRulesService.getDefaultZoneId()).minusDays(JOINER_WINDOW_DAYS - 1);
            List<ManagerDashboardDto.TeamJoiner> joiners = team.getTeamJoiners() == null ? List.of() : team.getTeamJoiners().stream()
                    .filter(j -> j.getJoinedTeamOn() != null && !LocalDate.parse(j.getJoinedTeamOn()).isBefore(since))
                    .sorted(Comparator.comparing(ManagerDashboardDto.TeamJoiner::getJoinedTeamOn).reversed())
                    .toList();
            out.append("\nJoined your team in the last %d days (%d): %s".formatted(JOINER_WINDOW_DAYS, joiners.size(),
                    joiners.isEmpty() ? "none" : joiners.stream()
                            .map(j -> j.getFullName() + " on " + j.getJoinedTeamOn()).collect(Collectors.joining(", "))));
            return Optional.of(out.toString());
        }
    }

    /**
     * Today's roster for the caller's direct reports, plus who was late over the last week - the
     * Team Attendance page and the Manager Home "Present today" tile, from the same two reads.
     */
    @Component
    @RequiredArgsConstructor
    public static class TeamAttendance implements AssistantDataProvider {

        private static final int RECENT_DAYS = 7;

        private final AttendanceService attendanceService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "team-attendance.today"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports' attendance today and this past week"; }
        @Override public Set<AudienceBucket> audiences() { return TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("team-attendance", "team", "attendance"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            String email = context.getActorEmail();
            // The org's business date, the same "today" the Team Attendance roster is keyed by.
            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            List<AttendanceResponse> roster = attendanceService.getDayForMyTeam(email, today);
            if (roster == null || roster.isEmpty()) return Optional.empty();

            StringBuilder out = new StringBuilder(rosterSummary(roster, today, "direct reports",
                    "the Manager Home page's Present Today tile"));

            LocalDate from = today.minusDays(RECENT_DAYS - 1);
            List<AttendanceResponse> week = attendanceService.getMonthForMyTeam(email, from, today);
            Map<String, List<String>> lateByPerson = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (week != null) {
                week.stream()
                        .filter(r -> "LATE".equals(r.getStatus()))
                        .sorted(Comparator.comparing(AttendanceResponse::getWorkDate).reversed())
                        .forEach(r -> lateByPerson.computeIfAbsent(r.getFullName(), k -> new java.util.ArrayList<>())
                                .add(r.getWorkDate().toString()));
            }
            out.append("\nLate arrivals (status LATE) from %s to %s, by person: %s".formatted(from, today,
                    lateByPerson.isEmpty() ? "none" : lateByPerson.entrySet().stream()
                            .map(e -> "%s %d (%s)".formatted(e.getKey(), e.getValue().size(), String.join(", ", e.getValue())))
                            .collect(Collectors.joining("; "))));
            return Optional.of(out.toString());
        }
    }

    /** Approved leave for the caller's direct reports, today and over the next two weeks. */
    @Component
    @RequiredArgsConstructor
    public static class TeamLeave implements AssistantDataProvider {

        private static final int WINDOW_DAYS = 14;

        private final LeaveService leaveService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "team-leave.upcoming"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports' approved leave, today and the next 14 days"; }
        @Override public Set<AudienceBucket> audiences() { return TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("team-leave", "team", "leave"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            List<LeaveRequestResponse> leave = leaveService.listTeamLeave(context.getActorEmail(), today, today.plusDays(WINDOW_DAYS - 1));
            return Optional.of(leaveSummary(leave, today, WINDOW_DAYS, "of your direct reports"));
        }
    }

    /**
     * Active (PENDING_REVIEW) attendance penalties for the caller's direct reports - the Regularize
     * &amp; Cancel Penalties screen's manager view, through the same read.
     *
     * <p>Reads {@link AttendancePenaltyService#listForDirectReports}, never {@code #list} -
     * {@code #list} widens to the whole organisation for an HR Admin or Super Admin, which would be
     * mislabelled "team" here; {@code OrganisationDataProviders.OrgPenalties} answers that question.
     */
    @Component
    @RequiredArgsConstructor
    public static class TeamPenalties implements AssistantDataProvider {

        private static final int LOOKBACK_DAYS = 90;
        private static final String PENDING_REVIEW = "PENDING_REVIEW";

        private final AttendancePenaltyService attendancePenaltyService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "team-penalties.active"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Active attendance penalties across your direct reports"; }
        @Override public Set<AudienceBucket> audiences() { return TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("team-penalties", "team", "penalties"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            LocalDate from = today.minusDays(LOOKBACK_DAYS - 1);
            List<AttendancePenaltyResponse> all = attendancePenaltyService.listForDirectReports(context.getActorEmail(), from, today);
            List<AttendancePenaltyResponse> active = all == null ? List.of()
                    : all.stream().filter(p -> PENDING_REVIEW.equals(p.getStatus())).toList();
            return Optional.of(penaltySummary(active, from, today, "your direct reports"));
        }
    }

    /**
     * Expense claims raised by the caller's direct reports, across their full lifecycle - the
     * Assets &amp; Expenses page's "Team" tab, through the same read.
     */
    @Component
    @RequiredArgsConstructor
    public static class TeamExpenseClaims implements AssistantDataProvider {

        private final ExpenseService expenseService;

        @Override public String id() { return "team-expenses.claims"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports' expense claims"; }
        @Override public Set<AudienceBucket> audiences() { return TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("team-expenses", "team", "assets"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            // Already newest-first (findByEmployeeUserIdInOrderByCreatedAtDesc) - see allTeamClaims.
            List<ExpenseClaimResponse> claims = expenseService.allTeamClaims(context.getActorEmail());
            if (claims == null || claims.isEmpty()) return Optional.empty();

            return Optional.of(LiveDataText.cappedList(claims, MAX_NAMES,
                    "expense claim(s) raised by your direct reports", "most recent",
                    c -> "%s: %s, %s on %s: %s".formatted(
                            c.getEmployeeName(), c.getCategoryName(), c.getAmount(), c.getExpenseDate(), c.getStatus())));
        }
    }

    /**
     * Asset requests raised by the caller's direct reports, across their full lifecycle - the
     * Assets &amp; Expenses page's "Team" tab, through the same read.
     */
    @Component
    @RequiredArgsConstructor
    public static class TeamAssetRequests implements AssistantDataProvider {

        private final AssetService assetService;

        @Override public String id() { return "team-assets.requests"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports' asset requests"; }
        @Override public Set<AudienceBucket> audiences() { return TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("team-assets", "team", "assets"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            // teamRequests makes no ordering guarantee, unlike allTeamClaims - sorted here so
            // "most recent" in the header below is actually true.
            List<AssetRequestResponse> requests = assetService.teamRequests(context.getActorEmail());
            if (requests == null || requests.isEmpty()) return Optional.empty();
            List<AssetRequestResponse> sorted = requests.stream()
                    .sorted(Comparator.comparing(AssetRequestResponse::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();

            return Optional.of(LiveDataText.cappedList(sorted, MAX_NAMES,
                    "asset request(s) raised by your direct reports", "most recent",
                    r -> "%s: %s - %s".formatted(r.getEmployeeName(), r.getCategoryName(), r.getStatus())));
        }
    }

    /** Assets currently assigned to the caller's direct reports - the Assets &amp; Expenses "Team" tab. */
    @Component
    @RequiredArgsConstructor
    public static class TeamAssetAssignments implements AssistantDataProvider {

        private final AssetService assetService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "team-assets.assignments"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Company assets currently assigned to your direct reports"; }
        @Override public Set<AudienceBucket> audiences() { return TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("team-assets", "team", "assets"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            // Already current-only (findByEmployeeUserIdInAndEffectiveToIsNull) - see teamAssignments.
            List<AssetAssignmentResponse> current = assetService.teamAssignments(context.getActorEmail());
            if (current == null || current.isEmpty()) return Optional.of("No company assets are currently assigned to your direct reports.");

            ZoneId zone = attendanceRulesService.getDefaultZoneId();
            return Optional.of(LiveDataText.cappedList(current, MAX_NAMES,
                    "asset(s) currently assigned across your direct reports", "first",
                    a -> "%s: %s (%s), assigned since %s, condition %s".formatted(a.getEmployeeName(), a.getAssetTag(),
                            a.getCategoryName(), LiveDataText.date(a.getEffectiveFrom(), zone),
                            a.getCondition() == null ? "not recorded" : a.getCondition())));
        }
    }

    // ---------------------------------------------------------------- shared with the org view

    /**
     * One day's roster summarised the way the Home dashboards count it: present = has a check-in,
     * late = status LATE, on leave = ON_LEAVE (approved leave with no punch), and the rest not in.
     */
    static String rosterSummary(List<AttendanceResponse> roster, LocalDate day, String population, String sameFigureAs) {
        List<AttendanceResponse> in = roster.stream().filter(r -> r.getCheckInAt() != null).toList();
        List<AttendanceResponse> late = roster.stream().filter(r -> "LATE".equals(r.getStatus())).toList();
        List<AttendanceResponse> onLeave = roster.stream().filter(r -> r.getCheckInAt() == null && "ON_LEAVE".equals(r.getStatus())).toList();
        List<AttendanceResponse> notIn = roster.stream().filter(r -> r.getCheckInAt() == null && !"ON_LEAVE".equals(r.getStatus())).toList();

        StringBuilder out = new StringBuilder("Today (%s): %d of %d %s have checked in - the same figure as %s."
                .formatted(day, in.size(), roster.size(), population, sameFigureAs));
        out.append("\n- Checked in (%d): %s".formatted(in.size(), LiveDataText.names(in.stream()
                .map(r -> r.getFullName() + " at " + LiveDataText.clock(r.getCheckInAt())).toList(), MAX_NAMES)));
        out.append("\n- Late today (%d): %s".formatted(late.size(), late.isEmpty() ? "none" : LiveDataText.names(late.stream()
                .map(r -> r.getLateByMinutes() == null ? r.getFullName() : r.getFullName() + " (" + r.getLateByMinutes() + " min past shift start)")
                .toList(), MAX_NAMES)));
        out.append("\n- On approved leave today, no punch (%d): %s".formatted(onLeave.size(),
                onLeave.isEmpty() ? "none" : LiveDataText.names(onLeave.stream().map(AttendanceResponse::getFullName).toList(), MAX_NAMES)));
        out.append("\n- Not checked in yet (%d): %s".formatted(notIn.size(),
                notIn.isEmpty() ? "none" : LiveDataText.names(notIn.stream().map(AttendanceResponse::getFullName).toList(), MAX_NAMES)));
        out.append("\n\"Not checked in yet\" is not the same as absent: it includes people on a weekly off or a holiday, "
                + "and people whose shift has not started.");
        return out.toString();
    }

    /** Approved leave in a window: who is off today (distinct people), then every request, latest first. */
    static String leaveSummary(List<LeaveRequestResponse> leave, LocalDate today, int windowDays, String population) {
        List<LeaveRequestResponse> rows = leave == null ? List.of() : leave;
        LocalDate to = today.plusDays(windowDays - 1);
        List<LeaveRequestResponse> offToday = rows.stream()
                .filter(r -> !today.isBefore(r.getStartDate()) && !today.isAfter(r.getEndDate()))
                .toList();
        Map<java.util.UUID, LeaveRequestResponse> distinctToday = new LinkedHashMap<>();
        offToday.forEach(r -> distinctToday.putIfAbsent(r.getEmployeeUserId(), r));

        StringBuilder out = new StringBuilder("On approved leave today (%s): %d %s - %s".formatted(today,
                distinctToday.size(), population, distinctToday.isEmpty() ? "nobody" : distinctToday.values().stream()
                        .map(r -> "%s (%s, %s to %s)".formatted(r.getEmployeeName(), r.getLeaveTypeName(),
                                LiveDataText.relative(r.getStartDate(), today), LiveDataText.relative(r.getEndDate(), today)))
                        .collect(Collectors.joining(", "))));
        if (rows.isEmpty()) {
            out.append("\nApproved leave in the next %d days, today included (%s to %s): none.".formatted(windowDays, today, to));
        } else {
            // Framed like MyHistory's headers: the window and its exact size stated up front, so a
            // "next two weeks" answer that drops the leave already in progress today visibly
            // contradicts the data instead of quietly reading "next" as "starting later".
            out.append("\nApproved leave in the next %d days, today included (%s to %s): exactly %d request(s), every one of "
                    .formatted(windowDays, today, to, rows.size())
                    + "which falls in that window - an answer about today, this week or the next two weeks must account for each.");
            // Cap the soonest-starting (the ones that matter first), then list them latest-first.
            List<LeaveRequestResponse> soonest = rows.stream()
                    .sorted(Comparator.comparing(LeaveRequestResponse::getStartDate))
                    .limit(MAX_NAMES)
                    .toList();
            out.append("\n").append(LiveDataText.listHeader(rows.size(), soonest.size(), "approved leave request(s)", "soonest-starting"));
            // "Covers today" is spelled out: leave that began before today still falls inside "the
            // next two weeks", but a model reading only start dates treats it as already over.
            soonest.stream()
                    .sorted(Comparator.comparing(LeaveRequestResponse::getStartDate).reversed())
                    .forEach(r -> out.append(inProgress(r, today)
                            // No day count on leave already under way: "2 days" beside today's date
                            // was read as "today and tomorrow" for a leave that ends today.
                            ? "\n- %s: %s, %s to %s%s%s".formatted(r.getEmployeeName(), r.getLeaveTypeName(),
                                    LiveDataText.relative(r.getStartDate(), today), LiveDataText.relative(r.getEndDate(), today),
                                    r.isHalfDay() ? ", half day" : "", todayNote(r, today))
                            : "\n- %s: %s, %s to %s (%s day(s))%s".formatted(r.getEmployeeName(), r.getLeaveTypeName(),
                                    LiveDataText.relative(r.getStartDate(), today), LiveDataText.relative(r.getEndDate(), today),
                                    r.getTotalDays(), r.isHalfDay() ? ", half day" : "")));
        }
        out.append("\nPending (not yet approved) leave is not included here - it sits in the Approval Center.");
        return out.toString();
    }

    /**
     * Whether a leave row is in progress today, and when it ends - stated outright, because given
     * only "2026-09-24 to 2026-09-25" and today's date the model has been seen calling a leave that
     * ends today one that "covers today and tomorrow".
     */
    private static String todayNote(LeaveRequestResponse r, LocalDate today) {
        if (!inProgress(r, today)) return "";
        return r.getEndDate().equals(today)
                ? " - on leave today; today is the final day of this leave"
                : " - on leave today, continuing until " + LiveDataText.relative(r.getEndDate(), today);
    }

    private static boolean inProgress(LeaveRequestResponse r, LocalDate today) {
        return !today.isBefore(r.getStartDate()) && !today.isAfter(r.getEndDate());
    }

    /** The penalty screen's list, narrowed to what the Attendance Log badges as PENALIZED. */
    static List<AttendancePenaltyResponse> activePenalties(AttendancePenaltyService service, String actorEmail,
                                                           LocalDate from, LocalDate to) {
        List<AttendancePenaltyResponse> all = service.list(actorEmail, from, to, null, null, null, null, null);
        return all == null ? List.of() : all.stream().filter(p -> "PENDING_REVIEW".equals(p.getStatus())).toList();
    }

    static String penaltySummary(List<AttendancePenaltyResponse> active, LocalDate from, LocalDate to, String population) {
        if (active.isEmpty()) return "No active attendance penalties for %s from %s to %s.".formatted(population, from, to);
        Map<String, Long> byType = active.stream().collect(Collectors.groupingBy(
                AttendancePenaltyResponse::getDiscrepancyType, TreeMap::new, Collectors.counting()));
        Map<String, Long> byPerson = active.stream().collect(Collectors.groupingBy(
                AttendancePenaltyResponse::getFullName, TreeMap::new, Collectors.counting()));
        StringBuilder out = new StringBuilder("Exactly %d active (PENDING_REVIEW) penalty(ies) for %s from %s to %s."
                .formatted(active.size(), population, from, to));
        out.append("\nBy discrepancy type: ").append(byType.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")));
        out.append("\nBy person: ").append(byPerson.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")));
        out.append("\n").append(LiveDataText.cappedList(active, MAX_NAMES, "penalty row(s)", "most recent",
                p -> "%s: %s - %s%s".formatted(p.getIncidentDate(), p.getFullName(), p.getDiscrepancyType(),
                        p.getDeductionDays() != null ? " (" + p.getDeductionDays() + " day(s) deducted)" : "")));
        return out.toString();
    }

    private static String orNotSet(String value) {
        return value == null || value.isBlank() ? "(not set)" : value;
    }
}

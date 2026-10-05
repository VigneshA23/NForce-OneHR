package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.assignments.EmployeeAssignmentRow;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.PunctualityLeaderboardEntry;
import com.nforce.onehr.dto.attendance.TeamEffortEntry;
import com.nforce.onehr.dto.attendance.TeamNegligenceResponse;
import com.nforce.onehr.dto.attendance.TeamPunctualityResponse;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.dto.reports.AttendanceRequestReportRow;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.service.AttendanceRequestService;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import com.nforce.onehr.service.DirectReportScopeService;
import com.nforce.onehr.service.EmployeeAssignmentService;
import com.nforce.onehr.service.LeaveService;
import com.nforce.onehr.service.ReportsService;
import com.nforce.onehr.service.WorkingDayService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * My Team's Efforts, Negligence, Time Assignments, Attendance Request Reports and the Overview
 * gaps {@code TeamDataProviders} does not already cover - "the same team-level information already
 * visible in the My Team module" (ONEHR - NORA My Team access control).
 *
 * <p><strong>Authorisation.</strong> Every read here goes through a service method that resolves
 * the caller's current direct reports itself, via the exact same repository query
 * ({@code EmployeeManagerHistoryRepository#findCurrentDirectReportIds}) that
 * {@link DirectReportScopeService} and every other never-widening My Team read in this codebase
 * already uses - never the caller-supplied audience, never a role name, and never a wider read
 * filtered down afterwards. Two of the underlying services ({@code AttendancePenaltyService},
 * {@code ReportsService}) also expose a second, role-widening method for their own org-wide
 * screens; this class calls only their direct-report-only counterpart
 * ({@code listForDirectReports}, {@code getAttendanceRequestReportForDirectReports}) - see
 * {@code DataProviderSafetyTest}, which asserts no provider anywhere reaches the widening one.
 *
 * <p><strong>Ranking is computed here, never left to the model.</strong> "Who was on time the
 * most" is answered from {@link LiveDataText#bestOf}, computed over the exact rows the backend
 * already ranked (never a second, independently-invented formula) - including every genuine tie,
 * per the My Team AI capability's own tie-handling requirement.
 *
 * <p><strong>No employee lookup by id, code or name exists anywhere in this class.</strong> Every
 * provider returns its whole (capped) direct-report population for its domain, exactly like
 * {@code TeamDataProviders.TeamMembers} already does - so "how many times was John late" is
 * answered by the model finding John's own row in a list that, structurally, can never contain
 * anyone who is not this caller's direct report. There is deliberately no code path that takes a
 * name or id from the question and looks up one specific person: that would be the one way an
 * unauthorised lookup could sneak in, and removing the path removes the risk rather than trying to
 * guard it.
 */
public final class MyTeamDataProviders {

    private MyTeamDataProviders() {}

    private static final int MAX_ROWS = 25;
    private static final Set<AudienceBucket> MY_TEAM_AUDIENCES = Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
    /** Matches the Efforts and Negligence tabs' own default (`useTeamDateRange(7)`). */
    private static final int DEFAULT_EFFORT_WINDOW_DAYS = 7;
    /** Matches the Penalties tab's own default (`useTeamDateRange(30)`), reused for request reports. */
    private static final int DEFAULT_REQUEST_WINDOW_DAYS = 30;

    /**
     * The Avg. Work Hours Leaderboard and the On-Time Leaderboard together - the My Team Efforts
     * tab is one screen for both (`EffortTab` renders `PunctualitySection` inline), so this is one
     * provider and one navigation target, not two.
     */
    @Component
    @RequiredArgsConstructor
    public static class MyTeamEffort implements AssistantDataProvider {

        private final AttendanceService attendanceService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "my-team-effort.leaderboard"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports' work hours and on-time leaderboard"; }
        @Override public Set<AudienceBucket> audiences() { return MY_TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("my-team-effort", "team-effort", "team-punctuality", "my-team", "team"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            return fetch(context, null);
        }

        @Override
        public Optional<String> fetch(AssistantRequestContext context, String question) {
            MyTeamDateRange.Range range = MyTeamDateRange.resolve(question,
                    LocalDate.now(attendanceRulesService.getDefaultZoneId()), DEFAULT_EFFORT_WINDOW_DAYS);
            List<TeamEffortEntry> effort = attendanceService.getTeamEffort(context.getActorEmail(), range.from(), range.to());
            TeamPunctualityResponse punctuality = attendanceService.getTeamPunctuality(context.getActorEmail(), range.from(), range.to());
            if (effort.isEmpty() && punctuality.getLeaderboard().isEmpty()) {
                return Optional.of("No direct-report attendance data is available for " + range.label() + ".");
            }

            StringBuilder out = new StringBuilder("Period: " + range.label() + ".");

            if (!effort.isEmpty()) {
                out.append("\nAvg. Work Hours Leaderboard - exactly %d direct report(s) with attendance in this period:"
                        .formatted(effort.size()));
                out.append("\nMost hours worked (highest avg. hrs/day): ")
                        .append(LiveDataText.bestOf(effort, TeamEffortEntry::getAvgHoursPerDay, true, MyTeamDataProviders::effortDesc));
                out.append("\nLeast hours worked (lowest avg. hrs/day): ")
                        .append(LiveDataText.bestOf(effort, TeamEffortEntry::getAvgHoursPerDay, false, MyTeamDataProviders::effortDesc));
                out.append("\n").append(LiveDataText.cappedList(effort, MAX_ROWS, "row(s) on the leaderboard, ranked highest avg. hrs/day first",
                        "highest first", MyTeamDataProviders::effortRow));
            }

            List<PunctualityLeaderboardEntry> board = punctuality.getLeaderboard();
            if (!board.isEmpty()) {
                out.append("\n\nOn-Time Leaderboard ('on time' = attendance status PRESENT) - exactly %d direct report(s) with expected working days in this period:"
                        .formatted(board.size()));
                out.append("\nHighest on-time percentage: ")
                        .append(LiveDataText.bestOf(board, PunctualityLeaderboardEntry::getPercentage, true, MyTeamDataProviders::punctualityDesc));
                out.append("\nLowest on-time percentage: ")
                        .append(LiveDataText.bestOf(board, PunctualityLeaderboardEntry::getPercentage, false, MyTeamDataProviders::punctualityDesc));
                out.append("\n").append(LiveDataText.cappedList(board, MAX_ROWS, "row(s) on the leaderboard, ranked highest on-time % first",
                        "highest first", MyTeamDataProviders::punctualityRow));
                out.append("\nDaily count of direct reports on time - avg. %.1f, min %d, max %d across this period."
                        .formatted(punctuality.getSummary().getAverageEmployeesOnTime(),
                                punctuality.getSummary().getMinimumEmployeesOnTime(),
                                punctuality.getSummary().getMaximumEmployeesOnTime()));
            }
            return Optional.of(out.toString());
        }
    }

    /** The three Negligence panels: Late Arrivals, Least Hours Worked, Frequent Breaks. */
    @Component
    @RequiredArgsConstructor
    public static class MyTeamNegligence implements AssistantDataProvider {

        private final AttendanceService attendanceService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "my-team-negligence.summary"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports' late arrivals, least hours worked and frequent breaks"; }
        @Override public Set<AudienceBucket> audiences() { return MY_TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("my-team-negligence", "team-negligence", "my-team", "team"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            return fetch(context, null);
        }

        @Override
        public Optional<String> fetch(AssistantRequestContext context, String question) {
            MyTeamDateRange.Range range = MyTeamDateRange.resolve(question,
                    LocalDate.now(attendanceRulesService.getDefaultZoneId()), DEFAULT_EFFORT_WINDOW_DAYS);
            TeamNegligenceResponse data = attendanceService.getTeamNegligence(context.getActorEmail(), range.from(), range.to());
            boolean empty = data.getLateArrivals().isEmpty() && data.getLeastHoursWorked().isEmpty() && data.getFrequentBreaks().isEmpty();
            if (empty) {
                return Optional.of("No direct-report attendance data is available for " + range.label() + ".");
            }

            StringBuilder out = new StringBuilder("Period: " + range.label() + ".");

            List<TeamNegligenceResponse.LateArrivalEntry> late = data.getLateArrivals();
            if (!late.isEmpty()) {
                out.append("\n\nLate Arrivals - exactly %d direct report(s) with at least one active day:".formatted(late.size()));
                out.append("\nMost late arrivals (highest late %): ")
                        .append(LiveDataText.bestOf(late, TeamNegligenceResponse.LateArrivalEntry::getLatePct, true, MyTeamDataProviders::lateDesc));
                out.append("\nFewest late arrivals (lowest late %): ")
                        .append(LiveDataText.bestOf(late, TeamNegligenceResponse.LateArrivalEntry::getLatePct, false, MyTeamDataProviders::lateDesc));
                out.append("\n").append(LiveDataText.cappedList(late, MAX_ROWS, "row(s), ranked highest late % first", "highest first",
                        MyTeamDataProviders::lateRow));
            }

            List<TeamNegligenceResponse.LeastHoursEntry> least = data.getLeastHoursWorked();
            if (!least.isEmpty()) {
                out.append("\n\nLeast Hours Worked - exactly %d direct report(s):".formatted(least.size()));
                out.append("\nLowest avg. hrs/day: ")
                        .append(LiveDataText.bestOf(least, TeamNegligenceResponse.LeastHoursEntry::getAvgHoursPerDay, false, MyTeamDataProviders::leastHoursDesc));
                out.append("\n").append(LiveDataText.cappedList(least, MAX_ROWS, "row(s), ranked lowest avg. hrs/day first", "lowest first",
                        MyTeamDataProviders::leastHoursRow));
            }

            List<TeamNegligenceResponse.FrequentBreaksEntry> breaks = data.getFrequentBreaks();
            if (!breaks.isEmpty()) {
                out.append("\n\nFrequent Breaks (employees with zero breaks in this period are not listed here) - exactly %d direct report(s):"
                        .formatted(breaks.size()));
                out.append("\nMost break time: ")
                        .append(LiveDataText.bestOf(breaks, TeamNegligenceResponse.FrequentBreaksEntry::getTotalBreakHours, true, MyTeamDataProviders::breaksDesc));
                out.append("\nHighest avg. breaks/day: ")
                        .append(LiveDataText.bestOf(breaks, TeamNegligenceResponse.FrequentBreaksEntry::getAvgBreaksPerDay, true, MyTeamDataProviders::breaksDesc));
                out.append("\n").append(LiveDataText.cappedList(breaks, MAX_ROWS, "row(s), ranked most break time first", "most first",
                        MyTeamDataProviders::breaksRow));
            }
            return Optional.of(out.toString());
        }
    }

    /** Shift, weekly-off and penalisation-policy assignments - the Employee Assignments tab. */
    @Component
    @RequiredArgsConstructor
    public static class MyTeamAssignments implements AssistantDataProvider {

        private final EmployeeAssignmentService employeeAssignmentService;

        @Override public String id() { return "my-team-assignments.roster"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports' shift, weekly-off and penalisation-policy assignments"; }
        @Override public Set<AudienceBucket> audiences() { return MY_TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("my-team-assignments", "team-assignments", "my-team", "team"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<EmployeeAssignmentRow> rows = employeeAssignmentService.listTeamAssignments(
                    context.getActorEmail(), null, null, null, null, null, null);
            if (rows.isEmpty()) return Optional.of("You have no direct reports to show assignments for.");

            StringBuilder out = new StringBuilder(LiveDataText.cappedList(rows, MAX_ROWS,
                    "direct report(s) with an assignment record", "by name", MyTeamDataProviders::assignmentRow));

            Map<String, List<String>> byShift = rows.stream()
                    .collect(Collectors.groupingBy(r -> r.getShiftName() == null ? "No shift assigned" : r.getShiftName(),
                            TreeMap::new, Collectors.mapping(EmployeeAssignmentRow::getFullName, Collectors.toList())));
            out.append("\nBy shift: ").append(byShift.entrySet().stream()
                    .map(e -> "%s (%d): %s".formatted(e.getKey(), e.getValue().size(), String.join(", ", e.getValue())))
                    .collect(Collectors.joining("; ")));

            long scheduled = rows.stream().filter(r -> r.getPendingShiftId() != null).count();
            if (scheduled > 0) {
                out.append("\nScheduled future shift changes (%d): %s".formatted(scheduled, rows.stream()
                        .filter(r -> r.getPendingShiftId() != null)
                        .map(r -> "%s to %s from %s".formatted(r.getFullName(), r.getPendingShiftName(), r.getPendingShiftEffectiveFrom()))
                        .collect(Collectors.joining("; "))));
            }
            return Optional.of(out.toString());
        }
    }

    /**
     * Attendance Request Reports - regularization, overtime, partial day, web clock-in and WFH,
     * across their full lifecycle. Reads {@link ReportsService#getAttendanceRequestReportForDirectReports},
     * never {@link ReportsService#getAttendanceRequestReport}, which widens to the whole
     * organisation for HR_ADMIN/SUPER_ADMIN - correct for the standalone Reports screen, wrong here.
     */
    @Component
    @RequiredArgsConstructor
    public static class MyTeamRequestReports implements AssistantDataProvider {

        private static final Map<ReportsService.ReportType, String> LABELS = Map.of(
                ReportsService.ReportType.REGULARIZATION, "Regularization",
                ReportsService.ReportType.OVERTIME, "Overtime",
                ReportsService.ReportType.PARTIAL_DAY, "Partial Day",
                ReportsService.ReportType.WEB_CLOCK_IN, "Web Clock-In",
                ReportsService.ReportType.WFH_OD, "Working Remotely (WFH)");

        private final ReportsService reportsService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "my-team-requests.report"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports' regularization, overtime, partial day, web clock-in and WFH requests"; }
        @Override public Set<AudienceBucket> audiences() { return MY_TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("my-team-requests", "team-requests", "my-team", "team"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            return fetch(context, null);
        }

        @Override
        public Optional<String> fetch(AssistantRequestContext context, String question) {
            MyTeamDateRange.Range range = MyTeamDateRange.resolve(question,
                    LocalDate.now(attendanceRulesService.getDefaultZoneId()), DEFAULT_REQUEST_WINDOW_DAYS);

            StringBuilder out = new StringBuilder("Period: " + range.label() + ".");
            boolean any = false;
            for (ReportsService.ReportType type : ReportsService.ReportType.values()) {
                List<AttendanceRequestReportRow> rows = reportsService.getAttendanceRequestReportForDirectReports(
                        context.getActorEmail(), type, range.from(), range.to());
                if (rows.isEmpty()) continue;
                any = true;
                long pending = rows.stream().filter(r -> "SUBMITTED".equals(r.getStatus()) || "PENDING".equals(r.getStatus())).count();
                out.append("\n\n%s - exactly %d request(s), %d still pending:".formatted(LABELS.get(type), rows.size(), pending));
                out.append("\n").append(LiveDataText.cappedList(rows, MAX_ROWS, "request(s), most recent first", "most recent",
                        MyTeamDataProviders::requestRow));
            }
            if (!any) return Optional.of("No direct-report requests of any of these types (regularization, overtime, "
                    + "partial day, web clock-in, WFH) in " + range.label() + ".");
            return Optional.of(out.toString());
        }
    }

    /**
     * The Overview tab's gaps {@code TeamDataProviders.TeamAttendance}/{@code TeamLeave} don't
     * already fill: who is working from home today, and each direct report's current-year leave
     * balances.
     *
     * <p>Deliberately does not also declare {@code team-attendance}: that module belongs to
     * {@code TeamDataProviders.TeamAttendance} (today's check-ins and late arrivals), a different
     * topic. Sharing it would only mean a "team attendance" match lit up two separate provider
     * families - {@code team-attendance} and {@code my-team-overview} - competing for one of the
     * turn's four slots for what the question asked once (ONEHR - a self-scoped "my attendance
     * records" question losing its own provider's slot to unrelated team matches was traced back to
     * exactly this kind of avoidable extra family competition).
     */
    @Component
    @RequiredArgsConstructor
    public static class MyTeamOverviewExtras implements AssistantDataProvider {

        private final AttendanceRequestService attendanceRequestService;
        private final LeaveService leaveService;
        private final DirectReportScopeService directReportScopeService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "my-team-overview.extras"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports on WFH today, and their current leave balances"; }
        @Override public Set<AudienceBucket> audiences() { return MY_TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("my-team-overview", "my-team", "team"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            // No team, no team block: an HR Admin with no reports was told "nobody in your team" beside
            // the organisation-wide figure they actually asked for (ONEHR).
            if (directReportScopeService.directReportIds(context.getActorEmail()).isEmpty()) return Optional.empty();
            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            List<AttendanceRequestResponse> wfh = attendanceRequestService.listTeamApprovedWfh(context.getActorEmail(), today, today);
            List<LeaveBalanceResponse> balances = leaveService.listTeamBalances(context.getActorEmail());

            // Stated even when nobody is: without this line "who is working from home today" had
            // no team block, and the model read that as "you have no direct reports" (ONEHR).
            StringBuilder out = new StringBuilder();
            out.append("Working from home today (%s), approved (%d): %s".formatted(today, wfh.size(),
                    wfh.isEmpty() ? "none" : wfh.stream()
                            .map(r -> r.getEmployeeName() + describeMode(r.getPartialDayMode()))
                            .collect(Collectors.joining(", "))));

            if (!balances.isEmpty()) {
                Map<UUID, String> namesById = directReportScopeService.directReports(context.getActorEmail()).stream()
                        .collect(Collectors.toMap(Employee::getUserId, Employee::getFullName, (a, b) -> a));
                out.append("\nCurrent-year leave balances - exactly %d row(s) across your direct reports:".formatted(balances.size()));
                out.append("\n").append(LiveDataText.cappedList(balances, MAX_ROWS, "row(s)", "by employee", b ->
                        "%s: %s - %s remaining of %s (used %s)".formatted(
                                namesById.getOrDefault(b.getEmployeeUserId(), "(unknown)"), b.getLeaveTypeName(),
                                b.getRemainingDays(), b.getTotalDays(), b.getUsedDays())));
            }
            return Optional.of(out.toString());
        }

        private static String describeMode(String mode) {
            if (mode == null) return "";
            return switch (mode) {
                case "FIRST_HALF" -> " (first half)";
                case "SECOND_HALF" -> " (second half)";
                default -> "";
            };
        }
    }

    /**
     * Absences, late arrivals, missed punches, half days and early departures per direct report over
     * the period the question names - "how many in my team were absent yesterday", "who has the most
     * missed punches this month", "who was late more than once". Counted here, per person, from the
     * rows My Team → Reports → Attendance Reports lists, so the model
     * quotes a count rather than scanning rows (ONEHR - it answered "absent yesterday" from the
     * manager's own empty record, and could not rank missed punches at all).
     */
    @Component
    @RequiredArgsConstructor
    public static class MyTeamAttendanceDiscrepancies implements AssistantDataProvider {

        /** Two months and a bit - the widest period read in one turn, most recent end kept. */
        private static final int MAX_DAYS = 62;
        private static final String ABSENT = "Absent (a working day for that person - not a weekly off, holiday or "
                + "approved leave - with no check-in; today is never counted, a shift may not have started)";
        private static final String LATE = "Late arrivals (status LATE)";
        private static final String MISSED = "Missed punches (checked in but never checked out)";
        private static final String HALF = "Half days (status HALF_DAY)";
        private static final String EARLY = "Left before the shift ended";
        private static final Map<String, String> NOUNS = Map.of(ABSENT, "absences", LATE, "late arrivals",
                MISSED, "missed punches", HALF, "half days", EARLY, "early departures");

        private final DirectReportScopeService directReportScopeService;
        private final AttendanceRepository attendanceRepository;
        private final AttendanceExceptionRepository attendanceExceptionRepository;
        private final WorkingDayService workingDayService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "my-team-attendance.discrepancies"; }
        @Override public DataScope scope() { return DataScope.TEAM; }
        @Override public String title() { return "Your direct reports' absences, late arrivals, missed punches, half days and early departures"; }
        @Override public Set<AudienceBucket> audiences() { return MY_TEAM_AUDIENCES; }
        @Override public Set<String> modules() { return Set.of("my-team-attendance", "team-attendance", "my-team", "team"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            return fetch(context, null);
        }

        /**
         * Raw rows in one query each, not {@code getMonthForMyTeam}: that resolves every row's shift
         * window with its own queries, and a month of a 22-person team took over a minute against
         * the hosted database. Only status, check-in and check-out are needed here; leaving early is
         * read from the EARLY_DEPARTURE exceptions the detector already worked out against each
         * row's own shift. Read-only transaction so each report's weekly-off policy can lazy-load.
         */
        @Override
        @Transactional(readOnly = true)
        public Optional<String> fetch(AssistantRequestContext context, String question) {
            String email = context.getActorEmail();
            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            MyTeamDateRange.Range range = MyTeamDateRange.resolve(question, today, DEFAULT_REQUEST_WINDOW_DAYS);
            LocalDate to = range.to();
            boolean truncated = range.from().isBefore(to.minusDays(MAX_DAYS - 1));
            LocalDate from = truncated ? to.minusDays(MAX_DAYS - 1) : range.from();

            List<Employee> reports = directReportScopeService.directReports(email);
            if (reports.isEmpty()) return Optional.of("You have no direct reports, so there is no team attendance to report.");
            List<UUID> ids = reports.stream().map(Employee::getUserId).toList();
            Map<UUID, String> names = names(reports);
            List<Attendance> rows = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(ids, from, to);

            Map<String, Map<String, List<LocalDate>>> byKind = new LinkedHashMap<>();
            for (String kind : List.of(ABSENT, LATE, MISSED, HALF, EARLY)) byKind.put(kind, new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

            LocalDate lastPastDay = to.isBefore(today) ? to : today.minusDays(1);
            if (!from.isAfter(lastPastDay)) {
                Map<UUID, Set<LocalDate>> punched = rows.stream().collect(Collectors.groupingBy(
                        Attendance::getEmployeeUserId, Collectors.mapping(Attendance::getWorkDate, Collectors.toSet())));
                Map<UUID, WorkingDaySchedule> schedules = workingDayService.computeExpectedWorkingDaysBulk(reports, from, lastPastDay);
                for (Employee e : reports) {
                    WorkingDaySchedule schedule = schedules.get(e.getUserId());
                    if (schedule == null) continue;
                    Set<LocalDate> in = punched.getOrDefault(e.getUserId(), Set.of());
                    schedule.getWorkingDates().stream().filter(d -> !in.contains(d))
                            .forEach(d -> add(byKind, ABSENT, names.get(e.getUserId()), d));
                }
            }
            for (Attendance r : rows) {
                String name = names.get(r.getEmployeeUserId());
                if ("LATE".equals(r.getStatus())) add(byKind, LATE, name, r.getWorkDate());
                if ("HALF_DAY".equals(r.getStatus())) add(byKind, HALF, name, r.getWorkDate());
                if ("MISSING_CHECKOUT".equals(r.getStatus())
                        || r.getWorkDate().isBefore(today) && r.getCheckInAt() != null && r.getCheckOutAt() == null) {
                    add(byKind, MISSED, name, r.getWorkDate());
                }
            }
            attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(ids, from, to)
                    .stream().filter(x -> ExceptionType.EARLY_DEPARTURE.equals(x.getExceptionType()))
                    .forEach(x -> add(byKind, EARLY, names.get(x.getEmployeeUserId()), x.getExceptionDate()));

            StringBuilder out = new StringBuilder("Period: %s. Exactly %d direct report(s): %s.".formatted(
                    truncated ? "the most recent %d days (%s to %s) of %s - say the list is partial".formatted(MAX_DAYS, from, to, range.label())
                            : range.label(),
                    reports.size(), LiveDataText.names(names.values(), MAX_ROWS)));
            byKind.forEach((kind, people) -> {
                int days = people.values().stream().mapToInt(List::size).sum();
                out.append("\n\n%s - exactly %d day(s) across %d of %d direct report(s)%s".formatted(
                        kind, days, people.size(), reports.size(), people.isEmpty() ? ": none." : ":"));
                if (people.isEmpty()) return;
                // Every line names its own type: with only "Repeated - more than once" under each
                // heading, the model quoted the absences line as late arrivals (ONEHR).
                String noun = NOUNS.get(kind);
                List<Map.Entry<String, List<LocalDate>>> ranked = people.entrySet().stream()
                        .sorted(Comparator.comparingInt((Map.Entry<String, List<LocalDate>> p) -> p.getValue().size()).reversed())
                        .toList();
                int most = ranked.get(0).getValue().size();
                List<String> top = ranked.stream().filter(p -> p.getValue().size() == most).map(Map.Entry::getKey).toList();
                out.append("\n- Most %s: %s with %d%s".formatted(noun, String.join(" and ", top), most, top.size() > 1 ? " each (tied)" : ""));
                List<String> repeated = ranked.stream().filter(p -> p.getValue().size() > 1).map(Map.Entry::getKey).toList();
                out.append("\n- Repeated %s - more than once (%d): %s".formatted(noun, repeated.size(), repeated.isEmpty() ? "nobody"
                        : ranked.stream().filter(p -> p.getValue().size() > 1)
                                .map(p -> p.getKey() + " " + p.getValue().size()).collect(Collectors.joining(", "))));
                out.append("\n- %s by person, most first: ".formatted(Character.toUpperCase(noun.charAt(0)) + noun.substring(1))).append(ranked.stream().limit(MAX_ROWS)
                        .map(p -> "%s %d (%s)".formatted(p.getKey(), p.getValue().size(), p.getValue().stream()
                                .sorted(Comparator.reverseOrder()).map(d -> LiveDataText.relative(d, today)).collect(Collectors.joining(", "))))
                        .collect(Collectors.joining("; ")));
            });
            return Optional.of(out.toString());
        }

        /** Full name, with the employee code added only where two direct reports share a name. */
        private static Map<UUID, String> names(List<Employee> reports) {
            Map<String, Long> counts = reports.stream().collect(Collectors.groupingBy(
                    e -> String.valueOf(e.getFullName()).toLowerCase(java.util.Locale.ROOT), Collectors.counting()));
            Map<UUID, String> names = new LinkedHashMap<>();
            reports.stream().sorted(Comparator.comparing(e -> String.valueOf(e.getFullName()), String.CASE_INSENSITIVE_ORDER))
                    .forEach(e -> names.put(e.getUserId(), counts.get(String.valueOf(e.getFullName()).toLowerCase(java.util.Locale.ROOT)) > 1
                            ? e.getFullName() + " (" + e.getEmployeeCode() + ")" : e.getFullName()));
            return names;
        }

        private static void add(Map<String, Map<String, List<LocalDate>>> byKind, String kind, String name, LocalDate date) {
            byKind.get(kind).computeIfAbsent(name == null ? "(unknown)" : name, n -> new ArrayList<>()).add(date);
        }
    }

    // ---------------------------------------------------------------- row/description formatting

    private static String effortDesc(TeamEffortEntry e) {
        return "%s (%.1f hrs/day)".formatted(e.getFullName(), e.getAvgHoursPerDay());
    }

    private static String effortRow(TeamEffortEntry e) {
        return "%s: avg. %.1f hrs/day, %.1f/%.0f hrs worked over %d active day(s)"
                .formatted(e.getFullName(), e.getAvgHoursPerDay(), e.getHoursWorked(), e.getExpectedHours(), e.getActiveDays());
    }

    private static String punctualityDesc(PunctualityLeaderboardEntry e) {
        return "%s at %.1f%% (%d/%d days)".formatted(e.getFullName(), e.getPercentage(), e.getOnTimeDays(), e.getExpectedWorkingDays());
    }

    private static String punctualityRow(PunctualityLeaderboardEntry e) {
        return "%s: %.1f%% on time (%d/%d expected working days)"
                .formatted(e.getFullName(), e.getPercentage(), e.getOnTimeDays(), e.getExpectedWorkingDays());
    }

    private static String lateDesc(TeamNegligenceResponse.LateArrivalEntry e) {
        return "%s at %.1f%% (%d/%d days)".formatted(e.getFullName(), e.getLatePct(), e.getLateDays(), e.getActiveDays());
    }

    private static String lateRow(TeamNegligenceResponse.LateArrivalEntry e) {
        return "%s: %d late day(s) of %d active (%.1f%%)".formatted(e.getFullName(), e.getLateDays(), e.getActiveDays(), e.getLatePct());
    }

    private static String leastHoursDesc(TeamNegligenceResponse.LeastHoursEntry e) {
        return "%s (%.1f hrs/day)".formatted(e.getFullName(), e.getAvgHoursPerDay());
    }

    private static String leastHoursRow(TeamNegligenceResponse.LeastHoursEntry e) {
        return "%s: avg. %.1f hrs/day, %.1f hrs worked".formatted(e.getFullName(), e.getAvgHoursPerDay(), e.getHoursWorked());
    }

    private static String breaksDesc(TeamNegligenceResponse.FrequentBreaksEntry e) {
        return "%s (%.1f hrs in %d break(s))".formatted(e.getFullName(), e.getTotalBreakHours(), e.getTotalBreakCount());
    }

    private static String breaksRow(TeamNegligenceResponse.FrequentBreaksEntry e) {
        return "%s: %.1f hrs across %d break(s), avg. %.1f breaks/day"
                .formatted(e.getFullName(), e.getTotalBreakHours(), e.getTotalBreakCount(), e.getAvgBreaksPerDay());
    }

    private static String assignmentRow(EmployeeAssignmentRow r) {
        String shift = r.getShiftName() == null ? "no shift assigned"
                : "%s (%s–%s %s)".formatted(r.getShiftName(), r.getShiftStartTime(), r.getShiftEndTime(),
                        r.getEmployeeTimezone() == null ? "" : r.getEmployeeTimezone());
        String pending = r.getPendingShiftId() == null ? ""
                : ", scheduled to change to %s from %s".formatted(r.getPendingShiftName(), r.getPendingShiftEffectiveFrom());
        return "%s (%s): shift %s%s, weekly off %s, penalisation policy %s".formatted(
                r.getFullName(), orNotSet(r.getEmployeeCode()), shift, pending,
                orNotSet(r.getWeeklyOffPolicyName()), orNotSet(r.getPenalisationPolicyName()));
    }

    private static String requestRow(AttendanceRequestReportRow r) {
        StringBuilder line = new StringBuilder("%s: %s - %s".formatted(r.getDate(), r.getFullName(), r.getStatus()));
        if (r.getRequestMode() != null) line.append(" (").append(r.getRequestMode()).append(')');
        if (r.getHours() != null) line.append(", ").append(r.getHours()).append(" hrs");
        return line.toString();
    }

    private static String orNotSet(String value) {
        return value == null || value.isBlank() ? "(not set)" : value;
    }
}

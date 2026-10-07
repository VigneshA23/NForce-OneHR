package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.ManagerDashboardDto;
import com.nforce.onehr.dto.attendance.AttendancePenaltyResponse;
import com.nforce.onehr.dto.org.BusinessUnitResponse;
import com.nforce.onehr.dto.org.DepartmentResponse;
import com.nforce.onehr.dto.org.DesignationResponse;
import com.nforce.onehr.dto.org.LocationResponse;
import com.nforce.onehr.dto.org.ShiftResponse;
import com.nforce.onehr.dto.org.WeeklyOffPolicyResponse;
import com.nforce.onehr.service.AttendancePenaltyService;
import com.nforce.onehr.service.AttendanceRequestService;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import com.nforce.onehr.service.EmployeeService;
import com.nforce.onehr.service.LeaveService;
import com.nforce.onehr.service.OrgService;
import com.nforce.onehr.service.UserManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Organisation-wide figures for HR Admins and Super Admins - the numbers on their Home dashboards,
 * User Management, Attendance and Leave Administration, and the organisation masters.
 *
 * <p>The service methods behind these screens mostly take no actor at all: {@code listUsers},
 * {@code getOrgDashboard}, {@code getDayForAll} and {@code listOrgLeave} are protected only by the
 * {@code @PreAuthorize} on the controller that serves them. So each provider's {@link #audiences}
 * mirrors that controller exactly - never wider - and {@code DataProviderSafetyTest} holds every
 * {@link DataScope#ORGANISATION} provider to HR/Admin audiences.
 *
 * <p>Where a screen computes its figure in the browser, the same computation is repeated here from
 * the same read, so the assistant and the screen agree to the unit (e.g. "active" is exactly
 * {@code EmployeeResponse.active}, which is what User Management and the Super Admin dashboard
 * count).
 */
public final class OrganisationDataProviders {

    private OrganisationDataProviders() {}

    private static final int MAX_NAMES = 25;
    private static final int MAX_GROUPS = 15;

    /**
     * User accounts as User Management lists them: totals, active vs inactive, by role, by
     * department, joined this month, and active employees with no manager - the same tiles as the
     * User Management page and the Super Admin dashboard. Super Admin only, matching
     * {@code UserManagementController}'s class-level {@code hasRole('SUPER_ADMIN')}.
     */
    @Component
    @RequiredArgsConstructor
    public static class UserAccounts implements AssistantDataProvider {

        private final UserManagementService userManagementService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "org-users.summary"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "User accounts in User Management"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.ADMIN); }
        @Override public Set<String> modules() { return Set.of("users", "headcount", "access", "administration"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<EmployeeResponse> users = userManagementService.listUsers();
            if (users == null || users.isEmpty()) return Optional.of("User Management lists no users.");

            List<EmployeeResponse> active = users.stream().filter(EmployeeResponse::isActive).toList();
            List<EmployeeResponse> inactive = users.stream().filter(u -> !u.isActive()).toList();
            LocalDate monthStart = LocalDate.now(attendanceRulesService.getDefaultZoneId()).withDayOfMonth(1);

            StringBuilder out = new StringBuilder(
                    "User Management lists exactly %d users: %d active and %d inactive (deactivated). "
                            .formatted(users.size(), active.size(), inactive.size())
                    + "Active means the account is enabled - the same flag the User Management page and the "
                    + "Super Admin dashboard count.");
            out.append("\nAll users by role: ").append(groupCounts(users, u -> PeopleDataProviders.roleLabel(u.getRole())));
            out.append("\nActive users by role: ").append(groupCounts(active, u -> PeopleDataProviders.roleLabel(u.getRole())));
            out.append("\nActive users by department: ").append(groupCounts(active, EmployeeResponse::getDepartmentName));
            out.append("\nActive users by location: ").append(groupCounts(active, EmployeeResponse::getLocationName));
            List<EmployeeResponse> joined = users.stream().filter(u -> u.getJoiningDate() != null && !u.getJoiningDate().isBefore(monthStart)).toList();
            out.append("\nJoined this month (joining date on or after %s): %d".formatted(monthStart, joined.size()));
            out.append("\nJoined per month over the last 12 months, by department:")
                    .append(joinersByMonth(users, EmployeeResponse::getJoiningDate, EmployeeResponse::getDepartmentName, monthStart));
            List<String> noManager = active.stream()
                    .filter(u -> u.getCurrentManager() == null && "EMPLOYEE".equals(u.getRole()))
                    .map(EmployeeResponse::getFullName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            out.append("\nActive employees with no reporting manager (%d): %s".formatted(noManager.size(),
                    noManager.isEmpty() ? "none" : LiveDataText.names(noManager, MAX_NAMES)));
            out.append("\nInactive users (%d): %s".formatted(inactive.size(), inactive.isEmpty() ? "none"
                    : LiveDataText.names(inactive.stream().map(EmployeeResponse::getFullName)
                            .sorted(String.CASE_INSENSITIVE_ORDER).toList(), MAX_NAMES)));
            return Optional.of(out.toString());
        }
    }

    /**
     * Organisation headcount as the HR Home dashboard and Reports compute it, from the same
     * {@code getOrgDashboard} read. HR Admin only: a Super Admin gets the richer
     * {@link UserAccounts} view of the same people instead of a second copy competing for the turn.
     */
    @Component
    @RequiredArgsConstructor
    public static class Headcount implements AssistantDataProvider {

        private final EmployeeService employeeService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "org-headcount.summary"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "Organisation headcount"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.HR); }
        @Override public Set<String> modules() { return Set.of("headcount", "users", "people", "reports"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            ManagerDashboardDto org = employeeService.getOrgDashboard();
            List<ManagerDashboardDto.DirectReport> people = org == null || org.getDirectReports() == null
                    ? List.of() : org.getDirectReports();
            if (people.isEmpty()) return Optional.of("No employees are on record.");

            List<ManagerDashboardDto.DirectReport> active = people.stream().filter(ManagerDashboardDto.DirectReport::isActive).toList();
            StringBuilder out = new StringBuilder("Exactly %d employees on record: %d active and %d inactive (deactivated accounts)."
                    .formatted(people.size(), active.size(), people.size() - active.size()));
            out.append("\nActive employees by role: ").append(groupCounts(active, p -> PeopleDataProviders.roleLabel(p.getRoleCode())));
            out.append("\nActive employees by department: ").append(groupCounts(active, ManagerDashboardDto.DirectReport::getDepartmentName));
            out.append("\nActive employees by designation: ").append(groupCounts(active, ManagerDashboardDto.DirectReport::getDesignationName));

            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            LocalDate monthStart = today.withDayOfMonth(1);
            List<ManagerDashboardDto.TeamJoiner> joiners = org.getTeamJoiners() == null ? List.of() : org.getTeamJoiners();
            List<ManagerDashboardDto.TeamJoiner> thisMonth = joiners.stream()
                    .filter(j -> j.getJoinedTeamOn() != null && !LocalDate.parse(j.getJoinedTeamOn()).isBefore(monthStart))
                    .sorted(Comparator.comparing(ManagerDashboardDto.TeamJoiner::getJoinedTeamOn).reversed())
                    .toList();
            out.append("\nNew joiners in the last 12 months: ").append(joiners.size());
            out.append("\nNew joiners this month (since %s) (%d): %s".formatted(monthStart, thisMonth.size(),
                    thisMonth.isEmpty() ? "none" : LiveDataText.names(thisMonth.stream().map(j -> j.getFullName() + " on " + j.getJoinedTeamOn())
                            .toList(), MAX_NAMES)));
            out.append("\nNew joiners per month over the last 12 months, by department:").append(joinersByMonth(joiners,
                    j -> j.getJoinedTeamOn() == null ? null : LocalDate.parse(j.getJoinedTeamOn()),
                    ManagerDashboardDto.TeamJoiner::getDepartmentName, monthStart));
            return Optional.of(out.toString());
        }
    }

    /**
     * Today's attendance across the organisation - Attendance Administration's day roster and the
     * HR/Super Admin Home "Present Today" tile, from the same {@code getDayForAll} read. Audiences
     * match {@code GET /api/attendance/day}'s {@code hasAnyRole('HR_ADMIN','SUPER_ADMIN')}.
     */
    @Component
    @RequiredArgsConstructor
    public static class OrgAttendanceToday implements AssistantDataProvider {

        private final AttendanceService attendanceService;
        private final AttendanceRulesService attendanceRulesService;
        private final EmployeeService employeeService;
        private final AttendanceRequestService attendanceRequestService;

        @Override public String id() { return "org-attendance.today"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "Organisation attendance for one day, by department"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.HR, AudienceBucket.ADMIN); }
        @Override public Set<String> modules() { return Set.of("org-attendance", "attendance"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            return fetch(context, null);
        }

        /**
         * A single past day the question names ("yesterday", "on 25-09-2026") is read instead of
         * today - the same {@code getDayForAll} read Attendance Administration's day roster makes for
         * any date - and broken down by department, so "how many in Quality Engineering were absent
         * yesterday" has a figure rather than "I can only show today" (ONEHR).
         */
        @Override
        public Optional<String> fetch(AssistantRequestContext context, String question) {
            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            LocalDate day = MyTeamDateRange.named(question, today)
                    .filter(r -> r.from().equals(r.to()) && !r.from().isAfter(today))
                    .map(MyTeamDateRange.Range::from)
                    .orElse(today);
            List<AttendanceResponse> roster = attendanceService.getDayForAll(day);
            if (roster == null || roster.isEmpty()) return Optional.empty();

            StringBuilder out = new StringBuilder(day.equals(today)
                    ? TeamDataProviders.rosterSummary(roster, today, "employees on the attendance roster", "the Home page's Present Today tile")
                    : pastDaySummary(roster, day, today));
            out.append("\nThe roster lists every employee record, including deactivated accounts, exactly as the dashboard does.");
            // HR and Super Admin see the whole organisation's WFH, not only their own direct reports (ONEHR).
            List<String> wfh = attendanceRequestService.listOrgApprovedWfh(day, day).stream()
                    .map(r -> r.getEmployeeName()).distinct().toList();
            out.append("\nWorking from home on %s, approved WFH across the organisation (%d): %s".formatted(
                    LiveDataText.relative(day, today), wfh.size(), wfh.isEmpty() ? "nobody" : LiveDataText.names(wfh, MAX_NAMES)));
            out.append(byDepartment(roster, day, today));
            return Optional.of(out.toString());
        }

        private static String pastDaySummary(List<AttendanceResponse> roster, LocalDate day, LocalDate today) {
            List<AttendanceResponse> in = roster.stream().filter(r -> r.getCheckInAt() != null).toList();
            List<String> onLeave = roster.stream().filter(r -> r.getCheckInAt() == null && "ON_LEAVE".equals(r.getStatus()))
                    .map(AttendanceResponse::getFullName).toList();
            List<String> absent = roster.stream().filter(r -> r.getCheckInAt() == null && !"ON_LEAVE".equals(r.getStatus()))
                    .map(AttendanceResponse::getFullName).toList();
            return ("On %s (a %s): %d of %d employees on the attendance roster checked in.".formatted(
                            LiveDataText.relative(day, today), day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                            in.size(), roster.size())
                    + "\n- On approved leave, no punch (%d): %s".formatted(onLeave.size(), onLeave.isEmpty() ? "none" : LiveDataText.names(onLeave, MAX_NAMES))
                    + "\n- Absent - no check-in and not on leave (%d): %s".formatted(absent.size(), absent.isEmpty() ? "none" : LiveDataText.names(absent, MAX_NAMES))
                    + "\n\"Absent\" here also counts anyone for whom that day was a weekly off or a holiday; say so if the day was one.");
        }

        /** Per department, active accounts only - a deactivated employee is not absent from anything. */
        private String byDepartment(List<AttendanceResponse> roster, LocalDate day, LocalDate today) {
            Map<String, DirectoryEntryDto> directory = employeeService.listDirectory().stream()
                    .collect(Collectors.toMap(DirectoryEntryDto::getUserId, Function.identity(), (a, b) -> a));
            Map<String, List<AttendanceResponse>> byDept = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (AttendanceResponse r : roster) {
                DirectoryEntryDto entry = r.getEmployeeUserId() == null ? null : directory.get(r.getEmployeeUserId().toString());
                if (entry == null || !entry.isActive()) continue;
                String dept = entry.getDepartmentName() == null || entry.getDepartmentName().isBlank() ? "(no department)" : entry.getDepartmentName();
                byDept.computeIfAbsent(dept, d -> new java.util.ArrayList<>()).add(r);
            }
            String missing = day.equals(today) ? "not checked in yet" : "absent (no check-in, not on leave)";
            StringBuilder out = new StringBuilder("\nBy department on %s, active employees only - checked in / on leave / %s:"
                    .formatted(LiveDataText.relative(day, today), missing));
            byDept.forEach((dept, rows) -> {
                List<String> notIn = rows.stream().filter(r -> r.getCheckInAt() == null && !"ON_LEAVE".equals(r.getStatus()))
                        .map(AttendanceResponse::getFullName).toList();
                long in = rows.stream().filter(r -> r.getCheckInAt() != null).count();
                long leave = rows.stream().filter(r -> r.getCheckInAt() == null && "ON_LEAVE".equals(r.getStatus())).count();
                out.append("\n- %s (%d active): %d checked in, %d on leave, %d %s%s".formatted(dept, rows.size(), in, leave,
                        notIn.size(), missing, notIn.isEmpty() ? "" : " - " + LiveDataText.names(notIn, MAX_NAMES)));
            });
            return out.toString();
        }
    }

    /**
     * Approved leave across the organisation, today and over the next two weeks - the HR Home
     * "On leave today" tile and Leave Administration, from the same {@code listOrgLeave} read.
     * Audiences match {@code GET /api/leave/organization}'s {@code hasAnyRole('HR_ADMIN','SUPER_ADMIN')}.
     */
    @Component
    @RequiredArgsConstructor
    public static class OrgLeave implements AssistantDataProvider {

        private static final int WINDOW_DAYS = 14;

        private final LeaveService leaveService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "org-leave.upcoming"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "Approved leave across the organisation, today and the next 14 days"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.HR, AudienceBucket.ADMIN); }
        @Override public Set<String> modules() { return Set.of("org-leave", "leave"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            List<LeaveRequestResponse> leave = leaveService.listOrgLeave(today, today.plusDays(WINDOW_DAYS - 1));
            return Optional.of(TeamDataProviders.leaveSummary(leave, today, WINDOW_DAYS, "employee(s) across the organisation"));
        }
    }

    /**
     * Active attendance penalties across the organisation - the Regularize &amp; Cancel Penalties
     * screen's HR view. The service scopes by the caller's own roles and returns every eligible
     * employee for an HR Admin or Super Admin, so this adds no scoping of its own.
     */
    @Component
    @RequiredArgsConstructor
    public static class OrgPenalties implements AssistantDataProvider {

        private static final int LOOKBACK_DAYS = 30;

        private final AttendancePenaltyService attendancePenaltyService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "org-penalties.active"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "Active attendance penalties across the organisation, last 30 days"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.HR, AudienceBucket.ADMIN); }
        @Override public Set<String> modules() { return Set.of("org-penalties", "penalties"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            LocalDate from = today.minusDays(LOOKBACK_DAYS - 1);
            List<AttendancePenaltyResponse> active = TeamDataProviders.activePenalties(
                    attendancePenaltyService, context.getActorEmail(), from, today);
            return Optional.of(TeamDataProviders.penaltySummary(active, from, today, "the organisation"));
        }
    }

    /**
     * The organisation masters - departments, designations, locations, business units, shifts,
     * weekly-off policies and leave types - with the employee counts Organization Structure /
     * Organization Masters show beside each one.
     */
    @Component
    @RequiredArgsConstructor
    public static class OrgStructure implements AssistantDataProvider {

        private final OrgService orgService;
        private final LeaveService leaveService;

        @Override public String id() { return "org-structure.summary"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "Organisation structure and master data"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.HR, AudienceBucket.ADMIN); }
        @Override public Set<String> modules() { return Set.of("organization", "administration", "leave-types"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            StringBuilder out = new StringBuilder("Employee counts are the ones shown beside each entry on the masters pages "
                    + "(they include deactivated accounts).");
            List<DepartmentResponse> departments = orgService.listDepartments();
            out.append(master("Departments", departments, DepartmentResponse::isActive, DepartmentResponse::getName, DepartmentResponse::getEmployeeCount));
            List<DesignationResponse> designations = orgService.listDesignations();
            out.append(master("Designations", designations, DesignationResponse::isActive, DesignationResponse::getTitle, DesignationResponse::getEmployeeCount));
            List<LocationResponse> locations = orgService.listLocations();
            out.append(master("Locations", locations, LocationResponse::isActive,
                    l -> l.getCity() == null ? l.getName() : l.getName() + " (" + l.getCity() + ")", LocationResponse::getEmployeeCount));
            List<BusinessUnitResponse> units = orgService.listBusinessUnits();
            out.append(master("Business units", units, BusinessUnitResponse::isActive, BusinessUnitResponse::getName, BusinessUnitResponse::getEmployeeCount));
            List<ShiftResponse> shifts = orgService.listShifts();
            out.append(master("Shifts", shifts, ShiftResponse::isActive,
                    s -> "%s %s-%s".formatted(s.getName(), LiveDataText.clock(s.getStartTime()), LiveDataText.clock(s.getEndTime())),
                    ShiftResponse::getEmployeeCount));
            List<WeeklyOffPolicyResponse> policies = orgService.listWeeklyOffPolicies();
            out.append(master("Weekly-off policies", policies, p -> true,
                    p -> p.getName() + " (" + String.join("/", p.getOffDays() == null ? List.of() : p.getOffDays()) + ")",
                    WeeklyOffPolicyResponse::getEmployeeCount));
            // The masters' Leave tab, from the same listTypes read. A leave type has no active flag
            // (no column, no toggle), so every configured type is an active one. A Super Admin with no
            // balances got no leave types at all and was sent to Organization Masters (ONEHR).
            List<LeaveTypeResponse> leaveTypes = Objects.requireNonNullElse(leaveService.listTypes(), List.of());
            out.append("\nLeave types: exactly %d configured, all active (leave types have no inactive state): %s".formatted(
                    leaveTypes.size(), leaveTypes.isEmpty() ? "none" : leaveTypes.stream()
                            .map(t -> "%s (%s)".formatted(t.getName(), t.getClassification().toLowerCase(Locale.ROOT)))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .collect(Collectors.joining(", "))));
            return Optional.of(out.toString());
        }

        private static <T> String master(String label, List<T> items, Predicate<T> active, Function<T, String> name,
                                         Function<T, Long> employees) {
            List<T> all = items == null ? List.of() : items;
            List<T> live = all.stream().filter(active).toList();
            String listed = live.stream()
                    .sorted(Comparator.comparing(employees).reversed())
                    .limit(MAX_GROUPS)
                    .map(i -> name.apply(i) + " " + employees.apply(i))
                    .collect(Collectors.joining(", "));
            return "\n%s: %d active, %d inactive. Active with employee counts: %s%s".formatted(label, live.size(),
                    all.size() - live.size(), listed.isEmpty() ? "none" : listed,
                    live.size() > MAX_GROUPS ? " (and %d more)".formatted(live.size() - MAX_GROUPS) : "");
        }
    }

    /**
     * One "- 2026-09 (last month): 33 - Engineering 11, ..." line per month with joiners, newest first,
     * over the 12 months ending with {@code monthStart}'s. Early in a month "this month" is usually
     * empty and "last month by department" was answered with directions (ONEHR), so every month is
     * given - with this month and last month labelled, as {@link LiveDataText#relative} does for days.
     */
    static <T> String joinersByMonth(List<T> people, Function<T, LocalDate> joined, Function<T, String> department, LocalDate monthStart) {
        YearMonth thisMonth = YearMonth.from(monthStart);
        Map<YearMonth, List<T>> byMonth = new TreeMap<>(Comparator.reverseOrder());
        for (T person : people) {
            LocalDate date = joined.apply(person);
            if (date == null) continue;
            YearMonth month = YearMonth.from(date);
            if (month.isBefore(thisMonth.minusMonths(11)) || month.isAfter(thisMonth)) continue;
            byMonth.computeIfAbsent(month, m -> new ArrayList<>()).add(person);
        }
        if (byMonth.isEmpty()) return " none";
        return byMonth.entrySet().stream()
                .map(e -> "\n- %s%s: %d - %s".formatted(e.getKey(),
                        e.getKey().equals(thisMonth) ? " (this month)" : e.getKey().equals(thisMonth.minusMonths(1)) ? " (last month)" : "",
                        e.getValue().size(), groupCounts(e.getValue(), department)))
                .collect(Collectors.joining());
    }

    /** "Name count, Name count" by descending count, empty/blank keys shown as "(not set)". */
    static <T> String groupCounts(List<T> items, Function<T, String> key) {
        Map<String, Long> counts = items.stream().collect(Collectors.groupingBy(
                i -> { String k = key.apply(i); return k == null || k.isBlank() ? "(not set)" : k; },
                TreeMap::new, Collectors.counting()));
        if (counts.isEmpty()) return "none";
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(MAX_GROUPS)
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "))
                + (counts.size() > MAX_GROUPS ? " (and %d smaller groups)".formatted(counts.size() - MAX_GROUPS) : "");
    }
}

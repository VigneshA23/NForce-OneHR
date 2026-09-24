package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.dto.attendance.AttendanceExceptionResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The caller's own attendance state.
 *
 * <p>Answers "am I clocked in", "did my punch register", and "why does Tuesday have an exception"
 * — the questions where a generic explanation of how attendance works is no use at all, because
 * the person already knows how it works and wants to know what happened to them.
 */
public final class AttendanceDataProviders {

    private AttendanceDataProviders() {}

    /** Today's punches and whether the caller can currently clock in or out. */
    @Component
    @RequiredArgsConstructor
    public static class Today implements AssistantDataProvider {

        private final AttendanceService attendanceService;

        @Override public String id() { return "attendance.today"; }
        @Override public String title() { return "Your attendance today"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            // Null timezone: the assistant has no browser to ask, and the service falls back to the
            // server's own zone. That is right for a stated fact about today rather than for a
            // clock-in, which is a write and is not something this feature can do.
            TodayAttendanceResponse today = attendanceService.getToday(context.getActorEmail(), null);
            if (today == null) return Optional.empty();

            StringBuilder out = new StringBuilder("Work date: ").append(today.getWorkDate());
            if (today.getRecord() == null) {
                out.append("\n- No attendance record for today yet.");
            } else {
                out.append("\n- Record exists for today.");
            }
            out.append("\n- Can clock in now: ").append(today.isCanCheckIn());
            out.append("\n- Can clock out now: ").append(today.isCanCheckOut());
            return Optional.of(out.toString());
        }
    }

    /** Recent attendance exceptions raised against the caller. */
    @Component
    @RequiredArgsConstructor
    public static class MyExceptions implements AssistantDataProvider {

        /**
         * Matches the regularization lookback window, so the exceptions shown are the ones the
         * person could still actually do something about. "7 days" here means the same thing the
         * regularization knowledge article documents it meaning elsewhere in this app: seven
         * calendar dates counting today, i.e. today and the six before it - not today-plus-seven.
         */
        private static final int LOOKBACK_DAYS = 7;

        /**
         * Headroom, not a display preference. A single day can carry more than one open exception
         * (ONEHR - a real employee's 7-day window held a LATE_ARRIVAL and a WORK_HOURS_SHORTAGE on
         * the same date, plus a MISSING_PUNCH alongside a LATE_ARRIVAL on another), so a 7-day
         * window's true row count can run well past one-per-day. The previous cap of 5 silently
         * dropped the two oldest rows of a real 7-row window before the model ever saw them, while
         * the header still claimed "exactly 5" - the model then correctly and confidently reported a
         * count that was already wrong at the data layer, which no prompt instruction can recover
         * from. This is set high enough that truncation within this window should not happen in
         * practice; the header below still tells the truth if it ever does.
         */
        private static final int MAX_ROWS = 20;

        private final AttendanceService attendanceService;
        private final EmployeeRepository employeeRepository;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "attendance.my-exceptions"; }
        @Override public String title() { return "Your attendance exceptions in the last 7 days"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance", "exceptions"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            // Was LocalDate.now() — the JVM/server's default zone, not the employee's (see
            // AttendanceService#resolveZone for the same employee-then-org-default fallback chain
            // used everywhere else "today" is computed). On a server not running in the org's own
            // timezone this silently shifted the 7-day lookback window by whatever the offset is,
            // same root cause as the "yesterday" date bug this was found alongside.
            ZoneId zone = employeeRepository.findByUser_Email(context.getActorEmail())
                    .map(attendanceRulesService::resolveEmployeeZoneId)
                    .orElseGet(attendanceRulesService::getDefaultZoneId);
            LocalDate today = LocalDate.now(zone);
            LocalDate from = today.minusDays(LOOKBACK_DAYS - 1);
            List<AttendanceExceptionResponse> exceptions = attendanceService.getMyExceptions(
                    context.getActorEmail(), from, today);
            if (exceptions == null || exceptions.isEmpty()) return Optional.empty();

            // States the exact window this data covers, in the model's own words for "the last 7
            // days" rather than leaving it to separately re-derive one from CURRENT DATE & TIME -
            // two independent computations of the same "last N days" phrase is exactly how a turn
            // ends up citing a window that doesn't match what was actually queried.
            //
            // The leading count and the numbered rows are both deliberate, not cosmetic: a plain
            // bulleted list plus a general "don't drop rows" policy instruction was NOT enough in
            // practice (ONEHR - chatbot dropped one matching exception from a "which days" answer
            // despite it being present in exactly this list) - a model asked to enumerate a handful
            // of items is measurably less likely to silently under-count when the source states its
            // own total up front and numbers each item against it, since a 2-of-3 answer then
            // visibly contradicts data the model was just given, rather than merely omitting an
            // item nothing else calls attention to.
            int total = exceptions.size();
            List<AttendanceExceptionResponse> limited = exceptions.stream().limit(MAX_ROWS).toList();
            String header = total <= limited.size()
                    ? "From %s to %s (today), exactly %d exception(s) in that range - your answer must account for all %d:"
                            .formatted(from, today, total, total)
                    : "From %s to %s (today), %d exception(s) in that range - only the most recent %d are listed below, say so if asked for the full list:"
                            .formatted(from, today, total, limited.size());

            // Grouped by type, ahead of the full itemised list. A question like "which days did I
            // come late" is really asking for a subset of this list filtered by type - and asking
            // the model to do that filtering itself, by scanning a flat mixed-type list, reproduced
            // the exact same silent-drop failure the numbered rows above were added to fix (ONEHR -
            // a real 7-row window with LATE_ARRIVAL mixed among WORK_HOURS_SHORTAGE/NO_ATTENDANCE/
            // MISSING_PUNCH still lost the oldest LATE_ARRIVAL from a "which days was I late"
            // answer, even once every row was present in the numbered list). Pre-grouping turns that
            // filter into a lookup: the model quotes the matching line instead of deriving it.
            Map<String, List<LocalDate>> byType = new LinkedHashMap<>();
            for (AttendanceExceptionResponse e : limited) {
                byType.computeIfAbsent(e.getExceptionType(), t -> new ArrayList<>()).add(e.getExceptionDate());
            }
            StringBuilder grouped = new StringBuilder("By type:");
            for (Map.Entry<String, List<LocalDate>> entry : byType.entrySet()) {
                grouped.append("\n- %s (%d): %s".formatted(entry.getKey(), entry.getValue().size(),
                        entry.getValue().stream().map(LocalDate::toString).collect(java.util.stream.Collectors.joining(", "))));
            }

            StringBuilder rows = new StringBuilder("Full detail:");
            for (int i = 0; i < limited.size(); i++) {
                AttendanceExceptionResponse e = limited.get(i);
                rows.append("\n%d. %s: %s (%s)".formatted(i + 1, e.getExceptionDate(), e.getExceptionType(), e.getStatus()));
            }
            // Kept as a short trailing note, not folded into the header above: an earlier version
            // appended this to the header sentence itself and that alone was enough to bring back the
            // exact silent-drop failure the header's own wording was tuned to prevent - a longer,
            // two-purpose opening sentence measurably diluted the "account for all N" instruction it
            // used to state on its own. Separately: a broad "do I have penalties" question was
            // answered from this exceptions section alone despite attendance.my-penalties being
            // present in the same turn (ONEHR - exception-only dates were stated as penalized that a
            // direct check of AttendancePenalty confirmed were never penalized at all), so the
            // distinction still needs to live next to this data, just not inside its counting header.
            String penaltyCaution = "\n\nNone of the above are penalties by themselves - see the separate "
                    + "active-penalties record for which dates, if any, actually have one.";
            return Optional.of(header + "\n\n" + grouped + "\n\n" + rows + penaltyCaution);
        }
    }

    /**
     * The caller's own active (PENDING_REVIEW) attendance penalties — deliberately the exact same
     * repository query and status filter {@code AttendanceService#historyFor} uses to decide the
     * Attendance Log's PENALIZED badge, so this can never disagree with what the employee sees on
     * screen for the same date (ONEHR - chatbot could not identify a penalization date even though
     * the Attendance Log clearly showed one). A separate concept from {@link MyExceptions}: an
     * exception is a detected discrepancy, a penalty is the deduction OneHR actually applied
     * because of one — "on which day was I penalized" needs this provider, not that one.
     */
    @Component
    @RequiredArgsConstructor
    public static class MyPenalties implements AssistantDataProvider {

        /** Penalties are far less frequent than daily exceptions, so this looks back further. */
        private static final int LOOKBACK_DAYS = 90;

        /** See {@link MyExceptions#MAX_ROWS} for why this is headroom, not a display preference. */
        private static final int MAX_ROWS = 20;

        private final AttendancePenaltyRepository attendancePenaltyRepository;
        private final EmployeeRepository employeeRepository;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "attendance.my-penalties"; }
        @Override public String title() { return "Your active attendance penalties"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance", "penalties"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            Optional<Employee> employee = employeeRepository.findByUser_Email(context.getActorEmail());
            if (employee.isEmpty()) return Optional.empty();

            ZoneId zone = attendanceRulesService.resolveEmployeeZoneId(employee.get());
            LocalDate today = LocalDate.now(zone);
            LocalDate from = today.minusDays(LOOKBACK_DAYS - 1);

            // PENDING_REVIEW only — a CANCELLED or REVERSED penalty is exactly what the badge on
            // the employee's own Attendance Log stops showing once it's resolved, so surfacing it
            // here as still "penalized" would contradict what they see on screen.
            List<AttendancePenalty> penalties = attendancePenaltyRepository
                    .findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                            employee.get().getUserId(), from, today, AttendancePenaltyStatus.PENDING_REVIEW);
            if (penalties == null || penalties.isEmpty()) return Optional.empty();

            // Explicit count + numbered rows, matching MyExceptions' own anti-omission fix — see
            // its comment for why a stated total measurably reduces a model silently under-listing.
            int total = penalties.size();
            List<AttendancePenalty> limited = penalties.stream()
                    .sorted(Comparator.comparing(AttendancePenalty::getIncidentDate).reversed())
                    .limit(MAX_ROWS)
                    .toList();
            String header = total <= limited.size()
                    ? "From %s to %s (today), exactly %d active penalty(ies) in that range - your answer must account for all %d:"
                            .formatted(from, today, total, total)
                    : "From %s to %s (today), %d active penalty(ies) in that range - only the most recent %d are listed below, say so if asked for the full list:"
                            .formatted(from, today, total, limited.size());
            StringBuilder rows = new StringBuilder();
            for (int i = 0; i < limited.size(); i++) {
                AttendancePenalty p = limited.get(i);
                rows.append("%d. %s: %s%s".formatted(i + 1, p.getIncidentDate(), p.getDiscrepancyType(),
                        p.getDeductionDays() != null ? " (%s day(s) deducted)".formatted(p.getDeductionDays()) : ""));
                if (i < limited.size() - 1) rows.append('\n');
            }
            return Optional.of(header + "\n" + rows);
        }
    }
}

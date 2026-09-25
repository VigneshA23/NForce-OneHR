package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.PunchResponse;
import com.nforce.onehr.dto.attendance.AttendanceConfigResponse;
import com.nforce.onehr.dto.attendance.AttendanceExceptionResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The caller's own attendance state.
 *
 * <p>Answers "am I clocked in", "did my punch register", and "why does Tuesday have an exception"
 * — the questions where a generic explanation of how attendance works is no use at all, because
 * the person already knows how it works and wants to know what happened to them.
 */
public final class AttendanceDataProviders {

    private AttendanceDataProviders() {}

    /**
     * The caller's attendance for their current work date: whether they are clocked in, when they
     * first checked in, how long they have worked so far, and whether they arrived late.
     *
     * <p>Previously reported only "record exists" and two can-clock-in/out flags, so "what time did
     * I check in today" or "how long have I worked" had no answer. Built from pure reads -
     * {@code currentWorkDate}, {@code getPunchForDate}, {@code getPunches}, {@code getConfig} -
     * rather than {@code getToday}, which also settles a stale open session as a side effect; the
     * assistant is read-only, and the figures are the same either way.
     */
    @Component
    @RequiredArgsConstructor
    public static class Today implements AssistantDataProvider {

        private final AttendanceService attendanceService;
        private final EmployeeRepository employeeRepository;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "attendance.today"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your attendance today"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance", "attendance-today"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            String email = context.getActorEmail();
            LocalDate workDate = attendanceService.currentWorkDate(email);
            AttendanceResponse record = attendanceService.getPunchForDate(email, workDate);
            List<PunchResponse> punches = attendanceService.getPunches(email, workDate);
            AttendanceConfigResponse config = attendanceService.getConfig(email);
            LocalDateTime now = LocalDateTime.now(zoneOf(email, employeeRepository, attendanceRulesService));

            StringBuilder out = new StringBuilder("Work date: %s (today)".formatted(workDate));
            if (config != null && config.getShiftStart() != null) {
                out.append("\n- Shift: %s, %s to %s".formatted(config.getShiftName(),
                        LiveDataText.clock(config.getShiftStart()), LiveDataText.clock(config.getShiftEnd())));
            }
            if (record == null && punches.isEmpty()) {
                out.append("\n- Not checked in yet today: no punch has been recorded for this work date.");
                return Optional.of(out.toString());
            }

            PunchResponse open = punches.stream()
                    .filter(p -> p.getCheckInAt() != null && p.getCheckOutAt() == null)
                    .reduce((first, second) -> second)
                    .orElse(null);
            out.append("\n- Currently clocked in: ")
                    .append(open != null ? "yes, since " + LiveDataText.clock(open.getCheckInAt()) : "no");

            LocalDateTime firstIn = record != null && record.getCheckInAt() != null
                    ? record.getCheckInAt()
                    : punches.get(0).getCheckInAt();
            out.append("\n- First check-in: ").append(LiveDataText.clock(firstIn));
            punches.stream().map(PunchResponse::getCheckOutAt).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .ifPresent(lastOut -> out.append("\n- Last check-out: ").append(LiveDataText.clock(lastOut)));

            // workedMinutes is the settled total of closed sessions only; an open session's time so
            // far is added the same way the Attendance page's live counter does.
            long worked = record != null && record.getWorkedMinutes() != null
                    ? record.getWorkedMinutes()
                    : punches.stream().filter(p -> p.getCheckOutAt() != null)
                            .mapToLong(p -> LiveDataText.minutesBetween(p.getCheckInAt(), p.getCheckOutAt())).sum();
            if (open != null) worked += LiveDataText.minutesBetween(open.getCheckInAt(), now);
            out.append("\n- Worked so far today: ").append(LiveDataText.hoursMinutes(worked))
                    .append(open != null ? " (including the session still open)" : "");
            out.append("\n- Check-in sessions today: ").append(punches.size());
            if (record != null && record.getStatus() != null) {
                out.append("\n- Day status: ").append(record.getStatus());
            }
            if (record != null) {
                out.append("\n- Arrival: ").append(lateness(record, config).map(l -> "late by " + l)
                        .orElse("on time (within the shift's grace period)"));
            }
            return Optional.of(out.toString());
        }
    }

    /**
     * The caller's attendance record for the last 30 days in one block: the same daily rows My
     * Attendance shows (through the same read, {@code getMyHistory}), every exception on those days,
     * and which of those exceptions carries an active penalty.
     *
     * <p>One provider, not three. Exceptions and penalties used to be separate providers, but every
     * attendance provider shares one family and the selector gives each family one slot before any
     * family gets a second - on My Attendance the regularization, overtime and request families took
     * the rest (ONEHR - "how many days was I late in the last 30 days, and do I have any active
     * penalties" got only the 7-day exceptions list: 3 late days where the page showed 7, and no
     * penalty data at all). The exception-to-penalty join is done here too, on the (date, type) key
     * the penalty engine writes a penalty against, so "which of these was I penalized for" is read
     * off each line rather than cross-referenced by the model.
     */
    @Component
    @RequiredArgsConstructor
    public static class MyHistory implements AssistantDataProvider {

        private static final int LOOKBACK_DAYS = 30;

        /** An active penalty stays PENDING_REVIEW until someone resolves it, so it is looked for further back. */
        private static final int PENALTY_LOOKBACK_DAYS = 90;

        private static final String LATE_ARRIVAL = "LATE_ARRIVAL";

        private final AttendanceService attendanceService;
        private final EmployeeRepository employeeRepository;
        private final AttendancePenaltyRepository attendancePenaltyRepository;

        @Override public String id() { return "attendance.my-history"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your attendance log, exceptions and active penalties"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance", "attendance-history", "exceptions", "penalties"); }

        private record Item(LocalDate date, String type, String detail) {}

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            String email = context.getActorEmail();
            LocalDate to = attendanceService.currentWorkDate(email);
            LocalDate from = to.minusDays(LOOKBACK_DAYS - 1);
            LocalDate penaltiesFrom = to.minusDays(PENALTY_LOOKBACK_DAYS - 1);
            List<AttendanceResponse> rows = Objects.requireNonNullElse(attendanceService.getMyHistory(email, from, to), List.of());
            List<AttendanceExceptionResponse> exceptions =
                    Objects.requireNonNullElse(attendanceService.getMyExceptions(email, from, to), List.of());
            AttendanceConfigResponse config = attendanceService.getConfig(email);
            // The same query and status filter historyFor uses for the page's PENALIZED badge, so
            // this can never disagree with what the employee sees for the same date.
            List<AttendancePenalty> penalties = employeeRepository.findByUser_Email(email)
                    .map(e -> attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                            e.getUserId(), penaltiesFrom, to, AttendancePenaltyStatus.PENDING_REVIEW))
                    .orElse(List.of()).stream()
                    .sorted(Comparator.comparing(AttendancePenalty::getIncidentDate).reversed())
                    .toList();

            // One item per (date, type); two exceptions on one date are two items and each counts.
            // Late arrivals come from the daily rows by the Arrival column's own rule, not from
            // LATE_ARRIVAL exception rows, which lag the page: detection never covers today, and
            // needs the day's status to still be LATE, which a short day relabelled HALF_DAY is not.
            Map<String, Item> items = new LinkedHashMap<>();
            for (AttendanceResponse r : rows) {
                lateness(r, config).ifPresent(l ->
                        items.put(r.getWorkDate() + "|" + LATE_ARRIVAL, new Item(r.getWorkDate(), LATE_ARRIVAL, "late by " + l)));
            }
            Set<LocalDate> regularized = rows.stream().filter(r -> "REGULARIZATION".equals(r.getSource()))
                    .map(AttendanceResponse::getWorkDate).collect(Collectors.toSet());
            for (AttendanceExceptionResponse e : exceptions) {
                if (LATE_ARRIVAL.equals(e.getExceptionType())) continue;
                items.putIfAbsent(e.getExceptionDate() + "|" + e.getExceptionType(), new Item(e.getExceptionDate(),
                        e.getExceptionType(), regularized.contains(e.getExceptionDate()) ? "day since corrected by regularization" : null));
            }
            // A penalty is always listed against its exception, even one the rules above did not find.
            Map<String, AttendancePenalty> penaltyByKey = new LinkedHashMap<>();
            for (AttendancePenalty p : penalties) {
                String key = p.getIncidentDate() + "|" + p.getDiscrepancyType();
                penaltyByKey.putIfAbsent(key, p);
                if (!p.getIncidentDate().isBefore(from)) items.putIfAbsent(key, new Item(p.getIncidentDate(), p.getDiscrepancyType(), null));
            }
            List<Item> ledger = items.values().stream().sorted(Comparator.comparing(Item::date).reversed()).toList();

            StringBuilder out = new StringBuilder("From %s to %s (today), newest first.".formatted(from, to));
            if (rows.isEmpty()) {
                out.append("\nNo attendance records at all in that range.");
            } else {
                long workedTotal = rows.stream().mapToLong(r -> r.getWorkedMinutes() == null ? 0 : r.getWorkedMinutes()).sum();
                out.append("\nSummary: exactly %d day(s) with an attendance record; total worked %s (average %s per recorded day)."
                        .formatted(rows.size(), LiveDataText.hoursMinutes(workedTotal), LiveDataText.hoursMinutes(workedTotal / rows.size())));
                appendStatusCount(out, rows, "HALF_DAY");
                appendStatusCount(out, rows, "MISSING_CHECKOUT");
            }

            out.append(penalties.isEmpty()
                    ? "\n\nActive penalties from %s to %s: none - you have no active attendance penalty.".formatted(penaltiesFrom, to)
                    : "\n\nActive penalties from %s to %s, each shown as PENALIZED on My Attendance - exactly %d:"
                            .formatted(penaltiesFrom, to, penalties.size()));
            for (int i = 0; i < penalties.size(); i++) {
                AttendancePenalty p = penalties.get(i);
                out.append("\n%d. %s: %s%s".formatted(i + 1, p.getIncidentDate(), p.getDiscrepancyType(), deduction(p)));
            }

            out.append(ledger.isEmpty()
                    ? "\n\nExceptions from %s to %s: none.".formatted(from, to)
                    : ("\n\nExceptions from %s to %s - exactly %d, one per line. Two on the same date are separate exceptions "
                            + "and each counts; each line says whether that exception is penalized:").formatted(from, to, ledger.size()));
            Map<String, List<LocalDate>> byType = new LinkedHashMap<>();
            for (int i = 0; i < ledger.size(); i++) {
                Item item = ledger.get(i);
                AttendancePenalty p = penaltyByKey.get(item.date() + "|" + item.type());
                out.append("\n%d. %s: %s%s - %s".formatted(i + 1, item.date(), item.type(),
                        item.detail() == null ? "" : " (" + item.detail() + ")",
                        p == null ? "not penalized" : "PENALIZED" + deduction(p)));
                byType.computeIfAbsent(item.type(), t -> new ArrayList<>()).add(item.date());
            }
            if (!ledger.isEmpty()) {
                out.append("\nBy type (LATE_ARRIVAL is the late days, matching My Attendance's Arrival column):");
                byType.forEach((type, dates) -> out.append("\n- %s (%d): %s".formatted(type, dates.size(),
                        dates.stream().map(LocalDate::toString).collect(Collectors.joining(", ")))));
            }

            if (rows.isEmpty()) return Optional.of(out.toString());
            out.append("\n\nA date with no row below has no attendance record - a weekly off, holiday, leave day or a day "
                    + "not worked. Do not call it absent unless the user's question establishes it was a working day.");
            out.append("\nDaily rows:");
            for (AttendanceResponse r : rows) {
                out.append("\n- %s (%s): %s, in %s, out %s, worked %s".formatted(
                        r.getWorkDate(),
                        r.getWorkDate().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                        r.getStatus() == null ? "no status" : r.getStatus(),
                        LiveDataText.clock(r.getCheckInAt()),
                        LiveDataText.clock(r.getCheckOutAt()),
                        LiveDataText.hoursMinutes(r.getWorkedMinutes() == null ? 0 : r.getWorkedMinutes())));
                lateness(r, config).ifPresent(l -> out.append(", late by ").append(l));
                if ("REGULARIZATION".equals(r.getSource())) out.append(", corrected by regularization");
            }
            return Optional.of(out.toString());
        }

        private static String deduction(AttendancePenalty p) {
            return p.getDeductionDays() == null ? "" : " (%s day(s) deducted)".formatted(p.getDeductionDays());
        }

        private static void appendStatusCount(StringBuilder out, List<AttendanceResponse> rows, String status) {
            List<String> dates = rows.stream().filter(r -> status.equals(r.getStatus()))
                    .map(r -> r.getWorkDate().toString()).toList();
            if (!dates.isEmpty()) {
                out.append("\n%s days (%d): %s".formatted(status, dates.size(), String.join(", ", dates)));
            }
        }
    }

    /** The caller's assigned shift, its grace period, the half-day threshold and their weekly offs. */
    @Component
    @RequiredArgsConstructor
    public static class MyShift implements AssistantDataProvider {

        private final AttendanceService attendanceService;

        @Override public String id() { return "attendance.my-shift"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your shift, grace period and weekly offs"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("attendance", "shift"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            AttendanceConfigResponse config = attendanceService.getConfig(context.getActorEmail());
            if (config == null) return Optional.empty();

            StringBuilder out = new StringBuilder();
            if (config.getShiftStart() == null) {
                out.append("- No shift is assigned to you right now (NO_SHIFT_ASSIGNED); HR assigns shifts.");
            } else {
                out.append("- Shift: %s, %s to %s".formatted(config.getShiftName(),
                        LiveDataText.clock(config.getShiftStart()), LiveDataText.clock(config.getShiftEnd())));
                out.append("\n- Late grace period: %d minutes - a check-in more than %d minutes after the shift start counts as late"
                        .formatted(config.getLateGraceMinutes(), config.getLateGraceMinutes()));
            }
            out.append("\n- Half-day threshold: %s hours".formatted(config.getHalfDayMaxHours()));
            if (config.getWeeklyOffDays() != null && !config.getWeeklyOffDays().isEmpty()) {
                out.append("\n- Weekly offs: ").append(String.join(", ", config.getWeeklyOffDays()));
            }
            return Optional.of(out.toString());
        }
    }

    /**
     * Lateness exactly as the Attendance page shows it: only when the raw minutes past shift start
     * exceed the shift's grace period (the page's LateBadge gate), and phrased from check-in minus
     * shift start to the second, falling back to the stored minutes for a legacy record.
     */
    static Optional<String> lateness(AttendanceResponse record, AttendanceConfigResponse config) {
        Integer minutes = record.getLateByMinutes();
        int grace = config != null ? config.getLateGraceMinutes() : 10;
        if (minutes == null || minutes <= grace) return Optional.empty();
        if (record.getCheckInAt() != null && record.getShiftStartAt() != null
                && record.getCheckInAt().isAfter(record.getShiftStartAt())) {
            long seconds = Duration.between(record.getShiftStartAt(), record.getCheckInAt()).getSeconds();
            long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
            return Optional.of(h > 0 ? "%dh %dm %ds".formatted(h, m, s) : "%dm %ds".formatted(m, s));
        }
        return Optional.of("%d minutes".formatted(minutes));
    }

    static ZoneId zoneOf(String email, EmployeeRepository employeeRepository, AttendanceRulesService attendanceRulesService) {
        return employeeRepository.findByUser_Email(email)
                .map(attendanceRulesService::resolveEmployeeZoneId)
                .orElseGet(attendanceRulesService::getDefaultZoneId);
    }
}

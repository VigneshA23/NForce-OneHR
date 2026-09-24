package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.dto.attendance.AttendanceExceptionResponse;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
         * person could still actually do something about.
         */
        private static final int LOOKBACK_DAYS = 7;
        private static final int MAX_ROWS = 5;

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
            List<AttendanceExceptionResponse> exceptions = attendanceService.getMyExceptions(
                    context.getActorEmail(), today.minusDays(LOOKBACK_DAYS), today);
            if (exceptions == null || exceptions.isEmpty()) return Optional.empty();

            return Optional.of(exceptions.stream()
                    .limit(MAX_ROWS)
                    .map(e -> "- %s: %s (%s)".formatted(
                            e.getExceptionDate(), e.getExceptionType(), e.getStatus()))
                    .collect(Collectors.joining("\n")));
        }
    }
}

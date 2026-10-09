package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.HolidayResponse;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.HolidayService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The holiday calendar for the caller's own location - the Leave &amp; Holidays page's calendar. */
public final class HolidayDataProviders {

    private HolidayDataProviders() {}

    /**
     * Upcoming holidays for the caller's location, through {@code getHolidaysForMyLocation} - never
     * {@code listAllHolidays}, which covers every location and would show one office another's days.
     */
    @Component
    @RequiredArgsConstructor
    public static class UpcomingHolidays implements AssistantDataProvider {

        private static final int MAX_ROWS = 15;

        private final HolidayService holidayService;
        private final EmployeeRepository employeeRepository;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "holiday.upcoming"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Holidays for your location"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("holidays", "leave"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<HolidayResponse> all = holidayService.getHolidaysForMyLocation(context.getActorEmail());
            if (all == null || all.isEmpty()) {
                return Optional.of("No holidays are configured for your location (or no location is set on your record).");
            }
            LocalDate today = LocalDate.now(AttendanceDataProviders.zoneOf(
                    context.getActorEmail(), employeeRepository, attendanceRulesService));
            List<HolidayResponse> upcoming = all.stream()
                    .filter(HolidayResponse::isActive)
                    .filter(h -> !h.getHolidayDate().isBefore(today))
                    .sorted(Comparator.comparing(HolidayResponse::getHolidayDate))
                    .toList();
            Optional<HolidayResponse> lastPast = all.stream()
                    .filter(HolidayResponse::isActive)
                    .filter(h -> h.getHolidayDate().isBefore(today))
                    .max(Comparator.comparing(HolidayResponse::getHolidayDate));

            String location = all.get(0).getLocationName();
            StringBuilder out = new StringBuilder("Location: %s. Today is %s.".formatted(location == null ? "(not set)" : location, today));
            if (upcoming.isEmpty()) {
                out.append("\nNo upcoming holidays are on the calendar.");
            } else {
                HolidayResponse next = upcoming.get(0);
                out.append("\nNext holiday: %s on %s (%s), in %d day(s).".formatted(next.getHolidayName(), next.getHolidayDate(),
                        day(next.getHolidayDate()), ChronoUnit.DAYS.between(today, next.getHolidayDate())));
                long thisYear = upcoming.stream().filter(h -> h.getHolidayDate().getYear() == today.getYear()).count();
                out.append("\nHolidays remaining this year: ").append(thisYear);
                // Cap the soonest ones (a latest-first cap would drop exactly the holidays people ask
                // about), then list them latest-first like every other date list the assistant gives.
                List<HolidayResponse> soonest = upcoming.stream().limit(MAX_ROWS).toList();
                out.append("\n").append(LiveDataText.listHeader(upcoming.size(), soonest.size(), "upcoming holiday(s)", "soonest"));
                soonest.stream()
                        .sorted(Comparator.comparing(HolidayResponse::getHolidayDate).reversed())
                        .forEach(h -> out.append("\n- %s (%s): %s".formatted(h.getHolidayDate(), day(h.getHolidayDate()), h.getHolidayName())));
            }
            lastPast.ifPresent(h -> out.append("\nMost recent past holiday: %s on %s.".formatted(h.getHolidayName(), h.getHolidayDate())));
            return Optional.of(out.toString());
        }

        private static String day(LocalDate date) {
            return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        }
    }
}

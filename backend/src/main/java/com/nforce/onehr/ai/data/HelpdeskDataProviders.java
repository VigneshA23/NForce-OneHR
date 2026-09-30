package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.helpdesk.HelpdeskDashboardDto;
import com.nforce.onehr.dto.helpdesk.TicketSummaryDto;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.HelpdeskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * HR service requests (help-desk tickets) - the caller's own tickets, and HR's queue.
 *
 * <p>Ticket descriptions and replies are free text and are never read here: status, category,
 * priority and assignee answer "where is my ticket" without sending anyone's words to the model.
 */
public final class HelpdeskDataProviders {

    private HelpdeskDataProviders() {}

    /** Tickets the caller raised, newest first, with status and who is handling each. */
    @Component
    @RequiredArgsConstructor
    public static class MyTickets implements AssistantDataProvider {

        private static final int PAGE_SIZE = 50;
        private static final int MAX_ROWS = 10;

        private final HelpdeskService helpdeskService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "helpdesk.my-tickets"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "HR service requests (tickets) you raised"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("helpdesk", "requests", "help"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            Page<TicketSummaryDto> page = helpdeskService.listMine(context.getActorEmail(), null, null, 0, PAGE_SIZE);
            List<TicketSummaryDto> tickets = page == null ? List.of() : page.getContent();
            long total = page == null ? 0 : page.getTotalElements();
            if (total == 0) return Optional.of("You have not raised any HR service requests.");

            ZoneId zone = attendanceRulesService.getDefaultZoneId();
            Map<String, Long> byStatus = tickets.stream().collect(Collectors.groupingBy(TicketSummaryDto::getStatus, TreeMap::new, Collectors.counting()));
            StringBuilder out = new StringBuilder("%d ticket(s) raised by you%s. By status%s: %s".formatted(total,
                    total > tickets.size() ? " (the %d most recent are counted below)".formatted(tickets.size()) : "",
                    total > tickets.size() ? " among those" : "",
                    byStatus.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))));
            out.append("\n").append(LiveDataText.cappedList(tickets, MAX_ROWS, "ticket(s)", "most recent", t ->
                    "%s (%s): %s, priority %s, %s, raised %s, last updated %s".formatted(t.getTicketNumber(), t.getCategoryName(),
                            t.getStatus(), t.getPriority(),
                            t.getAssignedToName() == null ? "not yet assigned" : "assigned to " + t.getAssignedToName(),
                            LiveDataText.date(t.getCreatedAt(), zone), LiveDataText.date(t.getUpdatedAt(), zone))));
            return Optional.of(out.toString());
        }
    }

    /**
     * HR's help-desk queue - the HR Service Requests page's status tiles and its active queue.
     * Audiences match {@code HrHelpdeskController}'s class-level {@code hasAnyRole('HR_ADMIN','SUPER_ADMIN')},
     * and both reads re-check that role themselves.
     */
    @Component
    @RequiredArgsConstructor
    public static class HelpdeskQueue implements AssistantDataProvider {

        private static final int MAX_ROWS = 10;

        private final HelpdeskService helpdeskService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "org-helpdesk.queue"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "HR service request (ticket) queue"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.HR, AudienceBucket.ADMIN); }
        @Override public Set<String> modules() { return Set.of("helpdesk-admin", "helpdesk", "requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            String email = context.getActorEmail();
            HelpdeskDashboardDto counts = helpdeskService.getDashboard(email);
            Page<TicketSummaryDto> active = helpdeskService.listQueue(email, List.of("OPEN", "IN_PROGRESS"), null, null, 0, MAX_ROWS);

            StringBuilder out = new StringBuilder();
            if (counts != null) {
                out.append("Tickets by status: %d open, %d in progress, %d resolved, %d closed."
                        .formatted(counts.getOpenCount(), counts.getInProgressCount(), counts.getResolvedCount(), counts.getClosedCount()));
            }
            List<TicketSummaryDto> rows = active == null ? List.of() : active.getContent();
            if (!rows.isEmpty()) {
                ZoneId zone = attendanceRulesService.getDefaultZoneId();
                out.append("\n").append(LiveDataText.listHeader((int) active.getTotalElements(), rows.size(), "active ticket(s) (open or in progress)", "first in the queue"));
                rows.forEach(t -> out.append("\n- %s (%s) from %s: %s, priority %s, %s, raised %s".formatted(
                        t.getTicketNumber(), t.getCategoryName(), t.getEmployeeName(), t.getStatus(), t.getPriority(),
                        t.getAssignedToName() == null ? "unassigned" : "assigned to " + t.getAssignedToName(),
                        LiveDataText.date(t.getCreatedAt(), zone))));
            }
            String text = out.toString().strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        }
    }
}

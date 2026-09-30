package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.NotificationDto;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The caller's own notifications - the bell in the top bar. */
public final class NotificationDataProviders {

    private NotificationDataProviders() {}

    /**
     * Unread count and the latest unread items. Scoped by the caller's user id, which the
     * assistant resolves from the authenticated principal exactly like {@code NotificationController}.
     */
    @Component
    @RequiredArgsConstructor
    public static class MyNotifications implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;
        private static final int MAX_MESSAGE_CHARS = 160;

        private final NotificationService notificationService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "notification.unread"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your unread notifications"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        // Only its own module: tagging it "dashboard" or "profile" would let the page hint hand it a
        // slot on every question asked from Home or My Profile, crowding out what was asked about.
        @Override public Set<String> modules() { return Set.of("notifications"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            long unread = notificationService.getUnreadCount(context.getUserId());
            if (unread == 0) return Optional.of("You have no unread notifications.");

            Page<NotificationDto> page = notificationService.getUnreadNotifications(context.getUserId(), 0, MAX_ROWS);
            List<NotificationDto> latest = page == null ? List.of() : page.getContent();
            ZoneId zone = attendanceRulesService.getDefaultZoneId();
            StringBuilder out = new StringBuilder(LiveDataText.listHeader((int) unread, latest.size(), "unread notification(s)", "most recent"));
            for (NotificationDto n : latest) {
                out.append("\n- %s: %s".formatted(LiveDataText.date(n.getCreatedAt(), zone), n.getTitle()));
                if (n.getMessage() != null && !n.getMessage().isBlank()) {
                    String message = n.getMessage().strip();
                    out.append(" - ").append(message.length() > MAX_MESSAGE_CHARS ? message.substring(0, MAX_MESSAGE_CHARS) + "..." : message);
                }
            }
            return Optional.of(out.toString());
        }
    }
}

package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.doc.AnnouncementResponse;
import com.nforce.onehr.dto.doc.PolicyResponse;
import com.nforce.onehr.service.AnnouncementService;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.PolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Company policies (with the caller's acknowledgement status) and published announcements. */
public final class PolicyDataProviders {

    private PolicyDataProviders() {}

    /** Policies published to the caller's roles and whether they have acknowledged each one. */
    @Component
    @RequiredArgsConstructor
    public static class MyPolicies implements AssistantDataProvider {

        private static final int MAX_ROWS = 15;

        private final PolicyService policyService;

        @Override public String id() { return "policy.my-acknowledgements"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Company policies and your acknowledgements"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("policies", "documents"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<PolicyResponse> policies = policyService.myPolicies(context.getActorEmail());
            if (policies == null || policies.isEmpty()) return Optional.of("No policies are published to you.");

            List<PolicyResponse> pending = policies.stream()
                    .filter(PolicyResponse::isRequired)
                    .filter(p -> !Boolean.TRUE.equals(p.getAcknowledged()))
                    .toList();
            long acknowledged = policies.stream().filter(p -> Boolean.TRUE.equals(p.getAcknowledged())).count();
            StringBuilder out = new StringBuilder("%d policy(ies) published to you; %d acknowledged; %d required and still awaiting your acknowledgement%s"
                    .formatted(policies.size(), acknowledged, pending.size(),
                            pending.isEmpty() ? "." : ": " + pending.stream().map(PolicyDataProviders::label).collect(Collectors.joining(", "))));
            out.append("\n").append(LiveDataText.cappedList(policies, MAX_ROWS, "policy(ies)", "first",
                    p -> "%s: %s%s".formatted(label(p), Boolean.TRUE.equals(p.getAcknowledged()) ? "acknowledged" : "not acknowledged",
                            p.isRequired() ? " (required)" : " (optional)")));
            return Optional.of(out.toString());
        }
    }

    /**
     * The latest published announcements. {@link DataScope#SHARED}: every role reads the same list
     * with no role check ({@code GET /api/announcements/published}), on My Documents &amp; Policies.
     */
    @Component
    @RequiredArgsConstructor
    public static class LatestAnnouncements implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;
        private static final int MAX_EXCERPT_CHARS = 240;

        private final AnnouncementService announcementService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "announcement.latest"; }
        @Override public DataScope scope() { return DataScope.SHARED; }
        @Override public String title() { return "Latest company announcements"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("announcements", "policies"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<AnnouncementResponse> published = announcementService.listPublished();
            if (published == null || published.isEmpty()) return Optional.of("No announcements are published.");

            ZoneId zone = attendanceRulesService.getDefaultZoneId();
            List<AnnouncementResponse> latest = published.stream()
                    .sorted(Comparator.comparing(AnnouncementResponse::getPublishedAt,
                            Comparator.nullsLast(Comparator.<Instant>naturalOrder())).reversed())
                    .toList();
            return Optional.of(LiveDataText.cappedList(latest, MAX_ROWS, "published announcement(s)", "most recent", a -> {
                String body = a.getBody() == null ? "" : a.getBody().strip().replaceAll("\\s+", " ");
                String excerpt = body.length() > MAX_EXCERPT_CHARS ? body.substring(0, MAX_EXCERPT_CHARS) + "..." : body;
                return "%s: %s%s".formatted(LiveDataText.date(a.getPublishedAt(), zone), a.getTitle(),
                        excerpt.isEmpty() ? "" : " - " + excerpt);
            }));
        }
    }

    private static String label(PolicyResponse p) {
        return p.getVersion() == null || p.getVersion().isBlank() ? p.getTitle() : p.getTitle() + " (v" + p.getVersion() + ")";
    }
}

package com.nforce.onehr.service;

import com.nforce.onehr.dto.helpdesk.AgentWorkloadDto;
import com.nforce.onehr.dto.helpdesk.CategoryCountDto;
import com.nforce.onehr.dto.helpdesk.ContentEngagementDto;
import com.nforce.onehr.dto.helpdesk.HelpdeskAnalyticsDto;
import com.nforce.onehr.dto.helpdesk.SatisfactionStatsDto;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HelpContentRepository;
import com.nforce.onehr.repository.HelpdeskTicketRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HR Help Desk reporting layer — everything {@link HelpdeskService#getDashboard} deliberately
 * doesn't cover (that one stays a 4-count KPI strip; this is the drill-down). Kept as its own
 * service rather than folded into HelpdeskService, same separation TalentDashboardService uses
 * for Talent & Skills — a reporting/aggregation concern with its own read-only queries, not part
 * of the ticket lifecycle itself.
 */
@Service
@RequiredArgsConstructor
public class HelpdeskAnalyticsService {

    private static final String PUBLISHED = "PUBLISHED";
    private static final int TOP_CONTENT_LIMIT = 5;
    private static final int ZERO_VIEW_CONTENT_LIMIT = 10;
    private static final int SATISFIED_THRESHOLD = 4;

    private final HelpdeskTicketRepository ticketRepo;
    private final HelpContentRepository helpContentRepo;
    private final UserRepository userRepo;
    private final EmployeeRepository employeeRepo;
    private final EffectivePermissionService effectivePermissionService;

    @Transactional(readOnly = true)
    public HelpdeskAnalyticsDto getAnalytics(String actorEmail) {
        requireAdmin(actorEmail);

        List<CategoryCountDto> categoryBreakdown = ticketRepo.countByCategory().stream()
                .map(row -> CategoryCountDto.builder()
                        .categoryName((String) row[0])
                        .ticketCount((Long) row[1])
                        .build())
                .toList();

        Double avgResolutionSeconds = ticketRepo.averageResolutionSeconds();

        List<AgentWorkloadDto> agentWorkload = buildAgentWorkload();
        SatisfactionStatsDto satisfaction = buildSatisfactionStats();

        List<com.nforce.onehr.entity.HelpContent> published = helpContentRepo.findByStatusOrderByViewCountDesc(PUBLISHED);
        List<ContentEngagementDto> topContent = published.stream()
                .limit(TOP_CONTENT_LIMIT)
                .map(this::toContentEngagement)
                .toList();
        List<ContentEngagementDto> zeroViewContent = published.stream()
                .filter(c -> c.getViewCount() == 0)
                .limit(ZERO_VIEW_CONTENT_LIMIT)
                .map(this::toContentEngagement)
                .toList();

        return HelpdeskAnalyticsDto.builder()
                .categoryBreakdown(categoryBreakdown)
                .averageResolutionHours(toHours(avgResolutionSeconds))
                .agentWorkload(agentWorkload)
                .satisfaction(satisfaction)
                .topContent(topContent)
                .zeroViewContent(zeroViewContent)
                .build();
    }

    private List<AgentWorkloadDto> buildAgentWorkload() {
        Map<UUID, Long> activeByAgent = ticketRepo.activeWorkloadByAgent().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        Map<UUID, Double> avgResolutionByAgent = ticketRepo.averageResolutionSecondsByAgent().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).doubleValue()));

        // Union, not intersection — an agent with only resolved (no currently-active) tickets, or
        // only active (nothing resolved yet), should still show up with the other side at zero/null.
        Set<UUID> agentIds = new LinkedHashSet<>();
        agentIds.addAll(activeByAgent.keySet());
        agentIds.addAll(avgResolutionByAgent.keySet());

        return agentIds.stream()
                .map(id -> AgentWorkloadDto.builder()
                        .agentId(id)
                        .agentName(employeeOrEmailName(id))
                        .activeTicketCount(activeByAgent.getOrDefault(id, 0L))
                        .averageResolutionHours(toHours(avgResolutionByAgent.get(id)))
                        .build())
                .sorted((a, b) -> Long.compare(b.getActiveTicketCount(), a.getActiveTicketCount()))
                .toList();
    }

    private SatisfactionStatsDto buildSatisfactionStats() {
        long ratingCount = ticketRepo.countBySatisfactionRatingIsNotNull();
        long satisfiedCount = ticketRepo.countBySatisfactionRatingGreaterThanEqual(SATISFIED_THRESHOLD);
        return SatisfactionStatsDto.builder()
                .averageRating(ticketRepo.averageSatisfactionRating())
                .ratingCount(ratingCount)
                .percentSatisfied(ratingCount > 0 ? (satisfiedCount * 100.0 / ratingCount) : 0)
                .build();
    }

    private ContentEngagementDto toContentEngagement(com.nforce.onehr.entity.HelpContent c) {
        return ContentEngagementDto.builder()
                .id(c.getId())
                .title(c.getTitle())
                .type(c.getType())
                .viewCount(c.getViewCount())
                .build();
    }

    private Double toHours(Double seconds) {
        return seconds != null ? seconds / 3600.0 : null;
    }

    private void requireAdmin(String actorEmail) {
        User actor = userRepo.findByEmail(actorEmail)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + actorEmail));
        // Same authority as HrHelpdeskController's /analytics gate — seeded to exactly HR_ADMIN +
        // SUPER_ADMIN, the roles this used to check by code.
        if (!effectivePermissionService.hasAuthority(actor, "helpdesk:VIEW:ALL")) {
            throw new AccessDeniedException("Help Desk permission required: helpdesk:VIEW:ALL");
        }
    }

    private String employeeOrEmailName(UUID userId) {
        return employeeRepo.findById(userId).map(e -> e.getFullName())
                .orElseGet(() -> userRepo.findById(userId).map(User::getEmail).orElse("Unknown"));
    }
}

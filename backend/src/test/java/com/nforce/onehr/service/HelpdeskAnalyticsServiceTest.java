package com.nforce.onehr.service;

import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import com.nforce.onehr.dto.helpdesk.HelpdeskAnalyticsDto;
import com.nforce.onehr.entity.HelpContent;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HelpContentRepository;
import com.nforce.onehr.repository.HelpdeskTicketRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HelpdeskAnalyticsServiceTest {

    @Mock private HelpdeskTicketRepository ticketRepo;
    @Mock private HelpContentRepository helpContentRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeRepository employeeRepo;

    @Spy private EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(java.util.Map.of(
            "CUSTOM_DESK_VIEWER", java.util.Map.of("helpdesk:VIEW", "ALL"),
            "CUSTOM_OTHER", java.util.Map.of("helpdesk:EDIT", "ALL")));
    @InjectMocks private HelpdeskAnalyticsService service;

    private final String hrAdminEmail = "hr@test.com";
    private final String employeeEmail = "employee@test.com";
    private final UUID agentId = UUID.randomUUID();

    private void stubEmptyAggregates() {
        when(ticketRepo.countByCategory()).thenReturn(List.of());
        when(ticketRepo.averageResolutionSeconds()).thenReturn(null);
        when(ticketRepo.activeWorkloadByAgent()).thenReturn(List.of());
        when(ticketRepo.averageResolutionSecondsByAgent()).thenReturn(List.of());
        when(ticketRepo.countBySatisfactionRatingIsNotNull()).thenReturn(0L);
        when(ticketRepo.countBySatisfactionRatingGreaterThanEqual(4)).thenReturn(0L);
        when(ticketRepo.averageSatisfactionRating()).thenReturn(null);
        when(helpContentRepo.findByStatusOrderByViewCountDesc("PUBLISHED")).thenReturn(List.of());
    }

    @Test
    void getAnalytics_byEmployee_isDenied() {
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(employeeEmail)
                        .roles(Set.of(Role.builder().code("EMPLOYEE").build())).build()));

        assertThrows(AccessDeniedException.class, () -> service.getAnalytics(employeeEmail));
        verifyNoInteractions(ticketRepo, helpContentRepo);
    }

    private static User customUser(String code) {
        return User.builder().id(UUID.randomUUID()).email(code.toLowerCase() + "@test.com")
                .roles(Set.of(Role.builder().id(100).code(code).displayName(code).build())).build();
    }

    @Test
    void getAnalytics_byCustomRoleWithHelpdeskView_isAllowed() {
        User viewer = customUser("CUSTOM_DESK_VIEWER");
        when(userRepo.findByEmail(viewer.getEmail())).thenReturn(Optional.of(viewer));
        stubEmptyAggregates();

        assertNotNull(service.getAnalytics(viewer.getEmail()));
    }

    @Test
    void getAnalytics_byCustomRoleWithoutHelpdeskView_isDenied() {
        User other = customUser("CUSTOM_OTHER");
        when(userRepo.findByEmail(other.getEmail())).thenReturn(Optional.of(other));

        assertThrows(AccessDeniedException.class, () -> service.getAnalytics(other.getEmail()));
        verifyNoInteractions(ticketRepo, helpContentRepo);
    }

    @Test
    void getAnalytics_withNoData_returnsNullAveragesNotExceptions() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(hrAdminEmail)
                        .roles(Set.of(Role.builder().code("HR_ADMIN").build())).build()));
        stubEmptyAggregates();

        HelpdeskAnalyticsDto dto = service.getAnalytics(hrAdminEmail);

        assertNull(dto.getAverageResolutionHours());
        assertNull(dto.getSatisfaction().getAverageRating());
        assertEquals(0, dto.getSatisfaction().getRatingCount());
        assertEquals(0, dto.getSatisfaction().getPercentSatisfied());
        assertTrue(dto.getCategoryBreakdown().isEmpty());
        assertTrue(dto.getAgentWorkload().isEmpty());
        assertTrue(dto.getTopContent().isEmpty());
        assertTrue(dto.getZeroViewContent().isEmpty());
    }

    @Test
    void getAnalytics_convertsResolutionSecondsToHours() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(hrAdminEmail)
                        .roles(Set.of(Role.builder().code("HR_ADMIN").build())).build()));
        stubEmptyAggregates();
        when(ticketRepo.averageResolutionSeconds()).thenReturn(7200.0); // 2 hours

        HelpdeskAnalyticsDto dto = service.getAnalytics(hrAdminEmail);

        assertEquals(2.0, dto.getAverageResolutionHours());
    }

    @Test
    void getAnalytics_categoryBreakdown_mapsRawRows() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(hrAdminEmail)
                        .roles(Set.of(Role.builder().code("HR_ADMIN").build())).build()));
        stubEmptyAggregates();
        List<Object[]> categoryRows = List.<Object[]>of(
                new Object[]{"Payroll", 12L}, new Object[]{"Leave", 5L});
        when(ticketRepo.countByCategory()).thenReturn(categoryRows);

        HelpdeskAnalyticsDto dto = service.getAnalytics(hrAdminEmail);

        assertEquals(2, dto.getCategoryBreakdown().size());
        assertEquals("Payroll", dto.getCategoryBreakdown().get(0).getCategoryName());
        assertEquals(12L, dto.getCategoryBreakdown().get(0).getTicketCount());
    }

    @Test
    void getAnalytics_agentWorkload_unionsActiveAndResolvedAgents() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(hrAdminEmail)
                        .roles(Set.of(Role.builder().code("HR_ADMIN").build())).build()));
        stubEmptyAggregates();
        UUID activeOnlyAgent = UUID.randomUUID();
        List<Object[]> activeRows = List.<Object[]>of(new Object[]{activeOnlyAgent, 3L});
        List<Object[]> avgRows = List.<Object[]>of(new Object[]{agentId, 3600.0});
        when(ticketRepo.activeWorkloadByAgent()).thenReturn(activeRows);
        when(ticketRepo.averageResolutionSecondsByAgent()).thenReturn(avgRows);
        when(employeeRepo.findById(any())).thenReturn(Optional.empty());
        when(userRepo.findById(any())).thenReturn(Optional.of(User.builder().email("agent@test.com").build()));

        HelpdeskAnalyticsDto dto = service.getAnalytics(hrAdminEmail);

        // Both agents appear even though neither has data on both sides of the union.
        assertEquals(2, dto.getAgentWorkload().size());
        var activeOnly = dto.getAgentWorkload().stream().filter(a -> a.getAgentId().equals(activeOnlyAgent)).findFirst().orElseThrow();
        assertEquals(3L, activeOnly.getActiveTicketCount());
        assertNull(activeOnly.getAverageResolutionHours());
        var resolvedOnly = dto.getAgentWorkload().stream().filter(a -> a.getAgentId().equals(agentId)).findFirst().orElseThrow();
        assertEquals(0L, resolvedOnly.getActiveTicketCount());
        assertEquals(1.0, resolvedOnly.getAverageResolutionHours());
    }

    @Test
    void getAnalytics_satisfaction_computesPercentSatisfied() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(hrAdminEmail)
                        .roles(Set.of(Role.builder().code("HR_ADMIN").build())).build()));
        stubEmptyAggregates();
        when(ticketRepo.countBySatisfactionRatingIsNotNull()).thenReturn(10L);
        when(ticketRepo.countBySatisfactionRatingGreaterThanEqual(4)).thenReturn(7L);
        when(ticketRepo.averageSatisfactionRating()).thenReturn(4.1);

        HelpdeskAnalyticsDto dto = service.getAnalytics(hrAdminEmail);

        assertEquals(4.1, dto.getSatisfaction().getAverageRating());
        assertEquals(10, dto.getSatisfaction().getRatingCount());
        assertEquals(70.0, dto.getSatisfaction().getPercentSatisfied());
    }

    @Test
    void getAnalytics_contentEngagement_splitsTopAndZeroView() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(hrAdminEmail)
                        .roles(Set.of(Role.builder().code("HR_ADMIN").build())).build()));
        stubEmptyAggregates();
        HelpContent popular = HelpContent.builder().id(UUID.randomUUID()).title("How to apply for leave").type("FAQ").viewCount(200).build();
        HelpContent neverViewed = HelpContent.builder().id(UUID.randomUUID()).title("Obscure policy").type("GUIDE").viewCount(0).build();
        // Repository contract: already sorted view-count descending.
        when(helpContentRepo.findByStatusOrderByViewCountDesc("PUBLISHED")).thenReturn(List.of(popular, neverViewed));

        HelpdeskAnalyticsDto dto = service.getAnalytics(hrAdminEmail);

        // Top content is just the first N of the already-sorted list (both items fit under the limit).
        assertEquals(2, dto.getTopContent().size());
        assertEquals("How to apply for leave", dto.getTopContent().get(0).getTitle());
        assertEquals(1, dto.getZeroViewContent().size());
        assertEquals("Obscure policy", dto.getZeroViewContent().get(0).getTitle());
    }
}

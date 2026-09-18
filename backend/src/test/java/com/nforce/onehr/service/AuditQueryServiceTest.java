package com.nforce.onehr.service;

import com.nforce.onehr.dto.audit.AuditLogStatsDto;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AuditLogRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit tests, mirroring RegularizationServiceTest/LeaveServiceTest's isolation
 * approach (this repo's H2 test profile can't create schema for the citext-typed entities).
 */
@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AuditTargetResolver targetResolver;
    // Deep stubs so the getCriteriaBuilder()/createQuery()/... chain used by the grouped stats
    // aggregate query resolves to further mocks all the way down to getResultList(), which
    // Mockito's default answer resolves to an empty list — equivalent to "no rows matched"
    // without needing a real persistence context (this suite deliberately avoids Spring/H2, see
    // class javadoc below).
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private EntityManager entityManager;

    @InjectMocks private AuditQueryService auditQueryService;

    private static final String CALLER_EMAIL = "caller@test.com";
    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Personal-scope resolution: every search/searchAll/stats call resolves the caller's own
        // id via this lookup, regardless of what other filters are passed.
        lenient().when(userRepository.findByEmail(CALLER_EMAIL))
                .thenReturn(Optional.of(User.builder().id(callerId).email(CALLER_EMAIL).build()));
    }

    // ── The security boundary: resolveAllowedActions ──

    @Test
    void resolveAllowedActions_hrAdmin_excludesAccessControl() {
        Set<String> allowed = auditQueryService.resolveAllowedActions(null, null, false);

        assertEquals(AuditActionCategory.HR_OPERATIONAL.actions(), allowed);
        for (String accessAction : AuditActionCategory.ACCESS_CONTROL.actions()) {
            assertFalse(allowed.contains(accessAction), accessAction + " leaked into HR Admin's allowed set");
        }
    }

    @Test
    void resolveAllowedActions_hrAdmin_requestingAccessGroup_returnsEmpty() {
        // A client-supplied group=ACCESS must never override the server-side category scope.
        Set<String> allowed = auditQueryService.resolveAllowedActions(null, AuditActionGroup.ACCESS, false);

        assertTrue(allowed.isEmpty());
    }

    @Test
    void resolveAllowedActions_hrAdmin_requestingAccessControlActionDirectly_returnsEmpty() {
        Set<String> allowed = auditQueryService.resolveAllowedActions("USER_DEACTIVATED", null, false);

        assertTrue(allowed.isEmpty());
    }

    @Test
    void resolveAllowedActions_hrAdmin_requestingHrOperationalAction_returnsThatSingleton() {
        Set<String> allowed = auditQueryService.resolveAllowedActions("USER_UPDATED", null, false);

        assertEquals(Set.of("USER_UPDATED"), allowed);
    }

    @Test
    void resolveAllowedActions_superAdmin_seesBothCategories() {
        Set<String> allowed = auditQueryService.resolveAllowedActions(null, null, true);

        assertEquals(AuditActionCategory.allActions(), allowed);
        assertTrue(allowed.containsAll(AuditActionCategory.ACCESS_CONTROL.actions()));
    }

    @Test
    void resolveAllowedActions_superAdmin_accessGroup_returnsOnlyAccessActions() {
        Set<String> allowed = auditQueryService.resolveAllowedActions(null, AuditActionGroup.ACCESS, true);

        assertEquals(AuditActionCategory.ACCESS_CONTROL.actions(), allowed);
    }

    // ── Personal-scope enforcement ──

    @Test
    void search_callerNotFound_throwsIllegalStateException() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                auditQueryService.search(null, null, null, null, null, 0, 20, true, "ghost@test.com"));
    }

    @Test
    void search_alwaysResolvesCallerIdentityRegardlessOfOtherFilters() {
        when(auditLogRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        auditQueryService.search(null, null, null, null, null, 0, 20, true, CALLER_EMAIL);

        // Confirms the mandatory self-scope resolution actually runs on every call — there is no
        // actor-search filter to opt into it; every row is already the caller's own by construction.
        verify(userRepository).findByEmail(CALLER_EMAIL);
    }

    // ── Short-circuit behavior (target search — the only remaining free-text filter) ──

    @Test
    void search_targetSearchWithNoMatches_returnsEmptyPageWithoutQueryingAuditLog() {
        when(targetResolver.resolveTargetIdsMatching("nobody")).thenReturn(Set.of());

        var page = auditQueryService.search("nobody", null, null, null, null, 0, 20, true, CALLER_EMAIL);

        assertEquals(0, page.getTotalElements());
        verify(auditLogRepository, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void search_targetSearchWithMatches_queriesAuditLog() {
        UUID matchedTarget = UUID.randomUUID();
        when(targetResolver.resolveTargetIdsMatching("vikram")).thenReturn(Set.of(matchedTarget));
        when(auditLogRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        auditQueryService.search("vikram", null, null, null, null, 0, 20, true, CALLER_EMAIL);

        verify(auditLogRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void stats_hrAdmin_omitsAccessGroupEntirely() {
        when(auditLogRepository.count(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(0L);

        AuditLogStatsDto stats = auditQueryService.stats(null, null, null, null, false, CALLER_EMAIL);

        assertFalse(stats.getByGroup().containsKey("ACCESS"));
    }

    @Test
    void stats_superAdmin_includesAccessGroup() {
        when(auditLogRepository.count(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(0L);

        AuditLogStatsDto stats = auditQueryService.stats(null, null, null, null, true, CALLER_EMAIL);

        assertTrue(stats.getByGroup().containsKey("ACCESS"));
    }
}

package com.nforce.onehr.service;

import com.nforce.onehr.entity.AuditLog;
import com.nforce.onehr.repository.AuditLogRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The Super Admin's org-wide "who changed access" view: what it covers, its 30-day default, and that it is not self-scoped. */
@ExtendWith(MockitoExtension.class)
class AuditAccessChangesTest {

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AuditTargetResolver targetResolver;
    @Mock private EntityManager entityManager;
    @InjectMocks private AuditQueryService service;

    @Test
    void defaultView_coversRoleUserAndPermissionChanges_includingRestores() {
        Set<String> actions = AuditActionCategory.accessChangeActions();

        assertTrue(actions.containsAll(Set.of("ROLE_PERMISSIONS_UPDATED", "ROLE_RESTORED", "ROLE_CHANGED", "USER_CREATED", "PASSWORD_RESET")));
    }

    @Test
    void defaultView_leavesOutTheHighVolumeSensitiveReads_butTheyCanBeChosenByName() {
        assertFalse(AuditActionCategory.accessChangeActions().contains("USER_SENSITIVE_DATA_VIEWED"));
        assertTrue(AuditActionCategory.accessViewSelectableActions().contains("USER_SENSITIVE_DATA_VIEWED"));
        assertTrue(AuditActionCategory.accessViewSelectableActions().contains("PROFILE_SENSITIVE_CHANGE_APPROVED"));
        assertFalse(AuditActionCategory.accessViewSelectableActions().contains("LEAVE_REQUEST_APPROVED"));
    }

    @Test
    void withoutAStartDate_theWindowIsTheLastThirtyDays_andAnExplicitDateWins() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");

        assertEquals(Instant.parse("2026-10-21T12:00:00Z"), AuditQueryService.accessChangesFrom(null, now));
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), AuditQueryService.accessChangesFrom(LocalDateTime.of(2026, 11, 1, 0, 0), now));
    }

    @Test
    void anActionOutsideTheAccessView_matchesNothing_andNeverTouchesTheTable() {
        Page<?> page = service.searchAccessChanges(null, null, "LEAVE_REQUEST_APPROVED", null, null, 0, 20);

        assertTrue(page.isEmpty());
        assertTrue(service.accessChangesAll(null, null, "LEAVE_REQUEST_APPROVED", null, null).isEmpty());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    void aSearchThatMatchesNobody_matchesNothing() {
        when(targetResolver.resolveTargetIdsMatching("nobody")).thenReturn(Set.of());

        assertTrue(service.searchAccessChanges("nobody", null, null, null, null, 0, 20).isEmpty());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void itIsNotLimitedToTheCallersOwnRows_unlikeTheMyActivityView() {
        when(auditLogRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.<AuditLog>empty());

        service.searchAccessChanges(null, null, null, null, null, 0, 20);

        verify(auditLogRepository).findAll(any(Specification.class), any(Pageable.class));
        // the actor-scoped spec resolves the caller first; the org-wide one must never do that
        verify(userRepository, never()).findByEmail(any());
    }
}

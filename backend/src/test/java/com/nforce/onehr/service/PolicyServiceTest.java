package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.PublishPolicyRequest;
import com.nforce.onehr.dto.doc.PublishPolicyVersionRequest;
import com.nforce.onehr.dto.doc.PolicyResponse;
import com.nforce.onehr.dto.doc.UpdatePolicyRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Policy;
import com.nforce.onehr.entity.PolicyAcknowledgment;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PolicyAcknowledgmentRepository;
import com.nforce.onehr.repository.PolicyRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ONEHR-349: the Policy Assignment notification's "Open Related Page" link must send every
 * recipient — regardless of role — to the same self-service Policies tab, never the HR/admin
 * policy-management page (/policies). See PolicyService#publish/#remindEmployee.
 */
@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

    private static final String POLICIES_TAB_LINK = "/my-documents?tab=policies";

    @Mock private PolicyRepository policyRepo;
    @Mock private PolicyAcknowledgmentRepository ackRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private PolicyService policyService;

    private static final String ADMIN_EMAIL = "hr-admin@test.com";

    @BeforeEach
    void setUp() {
        User actorAdmin = User.builder().id(UUID.randomUUID()).email(ADMIN_EMAIL)
                .roles(new HashSet<>(Set.of(role("HR_ADMIN")))).build();
        lenient().when(userRepo.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(actorAdmin));
    }

    private static Role role(String code) {
        int id = switch (code) {
            case "EMPLOYEE" -> 1;
            case "MANAGER" -> 2;
            case "HR_ADMIN" -> 3;
            case "SUPER_ADMIN" -> 4;
            default -> throw new IllegalArgumentException("Unknown role code: " + code);
        };
        return Role.builder().id(id).code(code).displayName(code).build();
    }

    @ParameterizedTest(name = "publish() routes {0} recipient to the shared self-service Policies tab")
    @ValueSource(strings = {"EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN"})
    void publish_routesEveryRoleToSharedPoliciesTab(String recipientRoleCode) {
        when(policyRepo.findByTitleOrderByPublishedAtDesc(anyString())).thenReturn(List.of());
        when(policyRepo.save(any(Policy.class))).thenAnswer(inv -> {
            Policy p = inv.getArgument(0);
            p.setId(99L);
            return p;
        });

        UUID recipientUserId = UUID.randomUUID();
        Employee recipient = Employee.builder()
                .userId(recipientUserId)
                .employeeCode("E-" + recipientRoleCode)
                .fullName("Recipient " + recipientRoleCode)
                .joiningDate(LocalDate.now())
                .build();
        when(employeeRepo.findActiveByRoleCodes(eq(Set.of(recipientRoleCode))))
                .thenReturn(List.of(recipient));

        PublishPolicyRequest req = new PublishPolicyRequest();
        req.setTitle("Code of Conduct");
        req.setVersion("1.0");
        req.setDescription("Be excellent to each other.");
        req.setAudience(recipientRoleCode);
        req.setRequired(true);

        policyService.publish(ADMIN_EMAIL, req);

        verify(notificationService).send(eq(recipientUserId), eq("POLICY_PUBLISHED"), anyString(), anyString(), eq(POLICIES_TAB_LINK));
    }

    @Test
    void remindEmployee_notifiesRecipientWithSharedPoliciesTabLink() {
        Policy policy = Policy.builder().id(5L).title("Code of Conduct").version("1.0").build();
        when(policyRepo.findById(5L)).thenReturn(Optional.of(policy));

        UUID recipientUserId = UUID.randomUUID();

        policyService.remindEmployee(ADMIN_EMAIL, 5L, recipientUserId);

        verify(notificationService).send(eq(recipientUserId), eq("POLICY_REMINDER"), anyString(), anyString(), eq(POLICIES_TAB_LINK));
    }

    // ── Policy Versioning with Forced Re-acknowledgment ─────────────────────────────────────

    @Test
    void editPolicy_metadataOnly_doesNotChangeVersionOrTouchAcknowledgments() {
        Policy policy = Policy.builder().id(7L).title("Old Title").version("1.0").versionNumber(1)
                .description("Old description").audience("ALL").required(true).active(true).build();
        when(policyRepo.findById(7L)).thenReturn(Optional.of(policy));
        when(policyRepo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdatePolicyRequest req = new UpdatePolicyRequest();
        req.setTitle("Fixed Typo Title");

        PolicyResponse resp = policyService.editPolicy(ADMIN_EMAIL, 7L, req);

        assertEquals("1.0", resp.getVersion());
        assertEquals(1, resp.getVersionNumber());
        assertTrue(resp.isActive());
        verifyNoInteractions(ackRepo);
    }

    @Test
    void publishNewVersion_incrementsVersionAndDeactivatesPreviousWithoutMutatingIt() {
        Policy current = Policy.builder().id(10L).title("Leave Policy").version("1.0").versionNumber(1)
                .description("Old content").audience("ALL").required(true).active(true).build();
        when(policyRepo.findById(10L)).thenReturn(Optional.of(current));
        when(policyRepo.saveAndFlush(current)).thenReturn(current);
        when(policyRepo.save(any(Policy.class))).thenAnswer(inv -> {
            Policy p = inv.getArgument(0);
            p.setId(11L);
            return p;
        });
        when(employeeRepo.findAllActiveWithDetails()).thenReturn(List.of());

        PublishPolicyVersionRequest req = new PublishPolicyVersionRequest();
        req.setVersion("2.0");
        req.setDescription("New substantive content");

        PolicyResponse resp = policyService.publishNewVersion(ADMIN_EMAIL, 10L, req);

        // Previous version deactivated but its own content/version untouched (history preserved).
        assertFalse(current.isActive());
        assertEquals("1.0", current.getVersion());
        assertEquals("Old content", current.getDescription());

        // New version is current, incremented, linked back to the previous one.
        assertEquals("2.0", resp.getVersion());
        assertEquals(2, resp.getVersionNumber());
        assertEquals(10L, resp.getPreviousVersionId());
        assertEquals("New substantive content", resp.getDescription());
        assertTrue(resp.isActive());
    }

    @Test
    void publishNewVersion_seedsFreshPendingAcknowledgmentsForTargetEmployees() {
        Policy current = Policy.builder().id(20L).title("IT Policy").version("1.0").versionNumber(1)
                .description("Old").audience("ALL").required(true).active(true).build();
        when(policyRepo.findById(20L)).thenReturn(Optional.of(current));
        when(policyRepo.saveAndFlush(current)).thenReturn(current);
        when(policyRepo.save(any(Policy.class))).thenAnswer(inv -> {
            Policy p = inv.getArgument(0);
            p.setId(21L);
            return p;
        });

        UUID employeeUserId = UUID.randomUUID();
        Employee emp = Employee.builder().userId(employeeUserId).employeeCode("E-1").fullName("Employee One")
                .joiningDate(LocalDate.now()).build();
        when(employeeRepo.findAllActiveWithDetails()).thenReturn(List.of(emp));

        PublishPolicyVersionRequest req = new PublishPolicyVersionRequest();
        req.setDescription("New content");

        policyService.publishNewVersion(ADMIN_EMAIL, 20L, req);

        verify(ackRepo).save(argThat(a -> a.getEmployeeUserId().equals(employeeUserId) && a.getAcknowledgedAt() == null));
        verify(notificationService).send(eq(employeeUserId), eq("POLICY_PUBLISHED"), anyString(), anyString(), eq(POLICIES_TAB_LINK));
        // Historical acknowledgments are never deleted when a new version publishes.
        verify(ackRepo, never()).deleteByPolicyId(any());
    }

    @Test
    void publishNewVersion_unauthorizedUserCannotPublish() {
        String employeeEmail = "employee@test.com";
        User employeeUser = User.builder().id(UUID.randomUUID()).email(employeeEmail)
                .roles(new HashSet<>(Set.of(role("EMPLOYEE")))).build();
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));

        PublishPolicyVersionRequest req = new PublishPolicyVersionRequest();
        req.setDescription("New content");

        assertThrows(AccessDeniedException.class, () -> policyService.publishNewVersion(employeeEmail, 30L, req));
        verify(policyRepo, never()).save(any());
    }

    @Test
    void getVersionHistory_returnsFullChainOldestFirst() {
        Policy v1 = Policy.builder().id(40L).title("Leave Policy").version("1.0").versionNumber(1).active(false).build();
        Policy v2 = Policy.builder().id(41L).title("Leave Policy").version("2.0").versionNumber(2)
                .previousVersionId(40L).active(false).build();
        Policy v3 = Policy.builder().id(42L).title("Leave Policy").version("3.0").versionNumber(3)
                .previousVersionId(41L).active(true).build();

        when(policyRepo.findById(41L)).thenReturn(Optional.of(v2));
        when(policyRepo.findById(40L)).thenReturn(Optional.of(v1));
        when(policyRepo.findByPreviousVersionId(41L)).thenReturn(Optional.of(v3));
        when(policyRepo.findByPreviousVersionId(42L)).thenReturn(Optional.empty());

        List<PolicyResponse> history = policyService.getVersionHistory(ADMIN_EMAIL, 41L);

        assertEquals(3, history.size());
        assertEquals(1, history.get(0).getVersionNumber());
        assertEquals(2, history.get(1).getVersionNumber());
        assertEquals(3, history.get(2).getVersionNumber());
    }
}

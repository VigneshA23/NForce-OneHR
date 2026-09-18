package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.PublishPolicyRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Policy;
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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
}

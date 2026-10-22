package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.dto.onboarding.StartOnboardingRequest;
import com.nforce.onehr.entity.Announcement;
import com.nforce.onehr.entity.DocumentType;
import com.nforce.onehr.entity.EmployeeDocument;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import com.nforce.onehr.service.search.impl.DocumentSearchProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V237: Documents / Policies / Announcements / Onboarding service gates are permission checks, not
 * role-code checks. For every migrated decision: (a) each system role is allowed exactly when it was
 * under the old role-code rule, (b) a custom role granted that permission is allowed, (c) a custom
 * role granted every OTHER permission of these modules is still denied.
 */
class ContentModulesCustomRolePermissionTest {

    private static final Set<String> HR_SA = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> SA = Set.of("SUPER_ADMIN");
    private static final List<String> MODULES = List.of("documents", "policies", "announcements", "onboarding");
    private static final List<String> SYSTEM = List.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN");
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final Long OWN = 1L;
    private static final Long OTHERS = 2L;

    interface Call {
        void run(Fixture f, String email) throws Exception;
    }

    /** key = the permission under test ("module:ACTION"); alsoNeeded = other grants the call needs to get that far. */
    record Case(String label, String key, Set<String> alsoNeeded, Set<String> oldHolders, Call call) {
        @Override public String toString() { return label; }
    }

    private static Case hrSa(String label, String key, Call call) {
        return new Case(label, key, Set.of(), HR_SA, call);
    }

    static Stream<Case> cases() {
        UUID id = UUID.randomUUID();
        return Stream.of(
                hrSa("documents.listPending", "documents:VIEW", (f, e) -> f.documents.listPending(e)),
                hrSa("documents.listAll", "documents:VIEW", (f, e) -> f.documents.listAll(e)),
                hrSa("documents.documentsForEmployee", "documents:VIEW", (f, e) -> f.documents.documentsForEmployee(e, id)),
                hrSa("documents.verify", "documents:APPROVE", (f, e) -> f.documents.verifyDocument(e, id, new VerifyDocumentRequest())),
                hrSa("documents.history (not owner)", "documents:VIEW", (f, e) -> f.documents.getDocumentHistory(e, id)),
                hrSa("documents.file (not owner)", "documents:VIEW", (f, e) -> f.documents.getDocumentFile(e, id)),
                hrSa("documents.listMissing", "documents:VIEW", (f, e) -> f.documents.listMissing(e)),
                hrSa("documents.remindMissing", "documents:VIEW", (f, e) -> f.documents.remindMissingDocument(e, id, 1)),
                hrSa("documents.kpis", "documents:VIEW", (f, e) -> f.documents.getAdminKpis(e)),

                hrSa("policies.listAll", "policies:VIEW", (f, e) -> f.policies.listAll(e)),
                hrSa("policies.publish", "policies:CREATE", (f, e) -> f.policies.publish(e, new PublishPolicyRequest(), null)),
                hrSa("policies.uploadAttachment", "policies:EDIT", (f, e) -> f.policies.uploadAttachment(e, 1L,
                        new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes()))),
                hrSa("policies.publishNewVersion", "policies:EDIT", (f, e) -> f.policies.publishNewVersion(e, 1L, new PublishPolicyVersionRequest(), null)),
                hrSa("policies.versionHistory", "policies:VIEW", (f, e) -> f.policies.getVersionHistory(e, 1L)),
                hrSa("policies.edit", "policies:EDIT", (f, e) -> f.policies.editPolicy(e, 1L, new UpdatePolicyRequest())),
                hrSa("policies.deactivate", "policies:EDIT", (f, e) -> f.policies.deactivatePolicy(e, 1L)),
                hrSa("policies.reactivate", "policies:EDIT", (f, e) -> f.policies.reactivatePolicy(e, 1L)),
                hrSa("policies.delete", "policies:DELETE", (f, e) -> f.policies.deletePolicy(e, 1L)),
                hrSa("policies.acknowledgments", "policies:VIEW", (f, e) -> f.policies.listAcknowledgments(e, 1L)),
                hrSa("policies.resetAcknowledgment", "policies:EDIT", (f, e) -> f.policies.resetAcknowledgment(e, 1L, id)),
                hrSa("policies.remind", "policies:EDIT", (f, e) -> f.policies.remindEmployee(e, 1L, id)),

                hrSa("announcements.listAll", "announcements:VIEW", (f, e) -> f.announcements.listAll(e)),
                hrSa("announcements.create", "announcements:CREATE", (f, e) -> f.announcements.create(e, new CreateAnnouncementRequest())),
                hrSa("announcements.publish own", "announcements:EDIT", (f, e) -> f.announcements.publish(e, OWN)),
                hrSa("announcements.update own", "announcements:EDIT", (f, e) -> f.announcements.update(e, OWN, new UpdateAnnouncementRequest())),
                hrSa("announcements.deactivate own", "announcements:EDIT", (f, e) -> f.announcements.deactivate(e, OWN)),
                hrSa("announcements.reactivate own", "announcements:EDIT", (f, e) -> f.announcements.reactivate(e, OWN)),
                hrSa("announcements.delete own", "announcements:DELETE", (f, e) -> f.announcements.delete(e, OWN)),
                // The old "only SUPER_ADMIN may act on another author's announcement" override.
                new Case("announcements.update another's", "announcements:CONFIGURE", Set.of("announcements:EDIT"), SA,
                        (f, e) -> f.announcements.update(e, OTHERS, new UpdateAnnouncementRequest())),
                new Case("announcements.publish another's", "announcements:CONFIGURE", Set.of("announcements:EDIT"), SA,
                        (f, e) -> f.announcements.publish(e, OTHERS)),
                new Case("announcements.deactivate another's", "announcements:CONFIGURE", Set.of("announcements:EDIT"), SA,
                        (f, e) -> f.announcements.deactivate(e, OTHERS)),
                new Case("announcements.reactivate another's", "announcements:CONFIGURE", Set.of("announcements:EDIT"), SA,
                        (f, e) -> f.announcements.reactivate(e, OTHERS)),
                new Case("announcements.delete another's", "announcements:CONFIGURE", Set.of("announcements:DELETE"), SA,
                        (f, e) -> f.announcements.delete(e, OTHERS)),

                hrSa("onboarding.start", "onboarding:CREATE", (f, e) -> {
                    StartOnboardingRequest req = new StartOnboardingRequest();
                    req.setEmployeeUserId(id);
                    f.onboarding.startOnboarding(req, e);
                }),
                hrSa("onboarding.listQueue", "onboarding:VIEW", (f, e) -> f.onboarding.listQueue(e)),
                hrSa("onboarding.eligibleEmployees", "onboarding:VIEW", (f, e) -> f.onboarding.eligibleEmployees(e)),
                hrSa("onboarding.eligibleEmployeesPaged", "onboarding:VIEW", (f, e) -> f.onboarding.eligibleEmployeesPaged(e, null, 0, 20)),
                hrSa("onboarding.searchQueue", "onboarding:VIEW", (f, e) -> f.onboarding.searchQueue(e, "IN_PROGRESS", null, 0, 20)),
                hrSa("onboarding.stats", "onboarding:VIEW", (f, e) -> f.onboarding.stats(e)),
                hrSa("onboarding.detail", "onboarding:VIEW", (f, e) -> f.onboarding.getDetail(id, e)),
                hrSa("onboarding.complete", "onboarding:APPROVE", (f, e) -> f.onboarding.completeOnboarding(id, e)),
                hrSa("onboarding.toggleItem", "onboarding:EDIT", (f, e) -> f.onboarding.toggleItem(id, id, e))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void gateMatchesOldRoleRuleForSystemRolesAndFollowsThePermissionForCustomRoles(Case c) throws Exception {
        Map<String, String> with = new HashMap<>();
        with.put(c.key(), "ALL");
        c.alsoNeeded().forEach(k -> with.put(k, "ALL"));
        Map<String, String> without = SeedMatrix.grants("SUPER_ADMIN").keySet().stream()
                .filter(k -> MODULES.contains(k.split(":")[0]) && !k.equals(c.key()))
                .collect(Collectors.toMap(k -> k, k -> "ALL"));
        Fixture f = new Fixture(SeedMatrix.permissionService(Map.of("CUSTOM_WITH", with, "CUSTOM_WITHOUT", without)));

        for (String code : SYSTEM) {
            assertEquals(c.oldHolders().contains(code), f.allowed(code, c.call()), c + " as " + code);
        }
        assertTrue(f.allowed("CUSTOM_WITH", c.call()), c + " as custom role granted " + c.key());
        assertFalse(f.allowed("CUSTOM_WITHOUT", c.call()), c + " as custom role lacking " + c.key());
    }

    @Test
    void announcementDraftsOfOthers_visibleOnlyWithManageAny() {
        Fixture f = new Fixture(SeedMatrix.permissionService(Map.of(
                "CUSTOM_WITH", Map.of("announcements:VIEW", "ALL", "announcements:CONFIGURE", "ALL"),
                "CUSTOM_WITHOUT", Map.of("announcements:VIEW", "ALL", "announcements:EDIT", "ALL"))));
        when(f.announcementRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                Announcement.builder().id(OTHERS).title("t").body("b").createdBy(OTHER_ID).createdAt(Instant.now()).build()));

        assertEquals(1, f.announcements.listAll(f.login("SUPER_ADMIN")).size());
        assertEquals(0, f.announcements.listAll(f.login("HR_ADMIN")).size());
        assertEquals(1, f.announcements.listAll(f.login("CUSTOM_WITH")).size());
        assertEquals(0, f.announcements.listAll(f.login("CUSTOM_WITHOUT")).size());
    }

    @Test
    void documentSearch_orgWideOnlyWithDocumentsViewAll() {
        Fixture f = new Fixture(SeedMatrix.permissionService(Map.of(
                "CUSTOM_WITH", Map.of("documents:VIEW", "ALL"),
                "CUSTOM_WITHOUT", Map.of("documents:APPROVE", "ALL", "documents:CONFIGURE", "ALL"))));
        DocumentService docs = mock(DocumentService.class);
        DocumentSearchProvider provider = new DocumentSearchProvider(docs, f.permissions);

        for (String code : List.of("HR_ADMIN", "SUPER_ADMIN", "CUSTOM_WITH")) {
            assertTrue(provider.refineUrl(f.user(code), "x").startsWith("/documents?"), code);
        }
        for (String code : List.of("EMPLOYEE", "MANAGER", "CUSTOM_WITHOUT")) {
            assertTrue(provider.refineUrl(f.user(code), "x").startsWith("/my-documents?"), code);
        }
    }

    /** All four services over plain mocks; every actor also holds EMPLOYEE, as every real user does. */
    static final class Fixture {
        final EffectivePermissionService permissions;
        final UserRepository userRepo = mock(UserRepository.class);
        final AnnouncementRepository announcementRepo = mock(AnnouncementRepository.class);
        final EmployeeDocumentRepository docRepo = mock(EmployeeDocumentRepository.class);
        final DocumentService documents;
        final PolicyService policies;
        final AnnouncementService announcements;
        final OnboardingService onboarding;
        private final Map<String, User> users = new HashMap<>();
        private UUID actorId;

        Fixture(EffectivePermissionService permissions) {
            this.permissions = permissions;
            EmployeeRepository employeeRepo = mock(EmployeeRepository.class);
            documents = new DocumentService(docRepo, mock(DocumentTypeRepository.class), employeeRepo, userRepo,
                    mock(NotificationRepository.class), mock(NotificationService.class), permissions);
            policies = new PolicyService(mock(PolicyRepository.class), mock(PolicyAcknowledgmentRepository.class), userRepo,
                    employeeRepo, mock(NotificationService.class), permissions);
            announcements = new AnnouncementService(announcementRepo, userRepo, permissions);
            onboarding = new OnboardingService(mock(OnboardingChecklistRepository.class), mock(OnboardingChecklistItemRepository.class),
                    employeeRepo, mock(EmployeeManagerHistoryRepository.class), userRepo, mock(EmployeeService.class),
                    mock(DocumentService.class), mock(AssetService.class), mock(AuditService.class),
                    mock(NotificationService.class), permissions);

            when(userRepo.findByEmail(anyString())).thenAnswer(inv -> Optional.ofNullable(users.get(inv.<String>getArgument(0))));
            // Someone else's document, so the owner exception never applies.
            when(docRepo.findById(any())).thenReturn(Optional.of(EmployeeDocument.builder().id(UUID.randomUUID())
                    .employeeUserId(OTHER_ID).documentType(DocumentType.builder().id(1).name("Passport").build()).build()));
            when(announcementRepo.findById(OWN)).thenAnswer(inv -> Optional.of(announcement(OWN, actorId)));
            when(announcementRepo.findById(OTHERS)).thenAnswer(inv -> Optional.of(announcement(OTHERS, OTHER_ID)));
        }

        private static Announcement announcement(Long id, UUID createdBy) {
            return Announcement.builder().id(id).title("t").body("b").createdBy(createdBy).createdAt(Instant.now()).build();
        }

        User user(String code) {
            Set<Role> roles = new HashSet<>(Set.of(SeedMatrix.role("EMPLOYEE")));
            roles.add(SYSTEM.contains(code) ? SeedMatrix.role(code)
                    : Role.builder().id(100 + code.length()).code(code).displayName(code).build());
            return User.builder().id(UUID.randomUUID()).email(code.toLowerCase() + "@test.com").active(true).roles(roles).build();
        }

        String login(String code) {
            User u = user(code);
            users.put(u.getEmail(), u);
            actorId = u.getId();
            return u.getEmail();
        }

        /** Passed the gate = anything but AccessDeniedException (mocks make the rest of the call fail harmlessly). */
        boolean allowed(String code, Call call) {
            try {
                call.run(this, login(code));
                return true;
            } catch (AccessDeniedException e) {
                return false;
            } catch (Exception | Error e) {
                if (e instanceof AssertionError ae) throw ae;
                return true;
            }
        }
    }
}

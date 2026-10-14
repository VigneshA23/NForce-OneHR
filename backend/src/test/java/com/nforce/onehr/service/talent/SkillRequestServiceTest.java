package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.talent.SkillRequestResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.dto.talent.CreateSkillRequestRequest;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import com.nforce.onehr.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillRequestServiceTest {

    @Mock private SkillRequestRepository skillRequestRepo;
    @Mock private SkillMasterRepository skillMasterRepo;
    @Mock private EmployeeSkillRepository employeeSkillRepo;
    @Mock private ProficiencyLevelRepository proficiencyLevelRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private NotificationService notificationService;
    @InjectMocks private SkillRequestService service;

    private static final UUID REQUESTER_ID = UUID.randomUUID();
    private static final UUID REVIEWER_ID = UUID.randomUUID();

    private static SkillRequest pendingRequest(String name) {
        return SkillRequest.builder().id(UUID.randomUUID()).requestedByUserId(REQUESTER_ID)
                .name(name).status("Pending").build();
    }

    private void stubReviewerAndNaming() {
        when(userRepository.findByEmail("hr@test.com")).thenReturn(Optional.of(User.builder().id(REVIEWER_ID).build()));
        when(employeeRepo.findById(REQUESTER_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(REQUESTER_ID)).thenReturn(Optional.of(User.builder().id(REQUESTER_ID).email("requester@test.com").build()));
    }

    @Test
    void submit_notifiesEveryActiveHrAdmin() {
        UUID hr1 = UUID.randomUUID();
        UUID hr2 = UUID.randomUUID();
        when(userRepository.findByEmail("employee@test.com"))
                .thenReturn(Optional.of(User.builder().id(REQUESTER_ID).email("employee@test.com").build()));
        when(skillRequestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepo.findById(REQUESTER_ID)).thenReturn(Optional.of(Employee.builder().fullName("Jane Doe").build()));
        when(userRepository.findActiveHrAdminUserIds()).thenReturn(Set.of(hr1, hr2));

        CreateSkillRequestRequest req = new CreateSkillRequestRequest();
        req.setName("Playwright");

        service.submit(req, "employee@test.com");

        verify(notificationService).send(eq(hr1), eq("SKILL_REQUEST_SUBMITTED"), eq("New Skill Request"),
                eq("Jane Doe has requested a new skill: Playwright."), eq("/talent-performance?tab=settings&sub=requests"));
        verify(notificationService).send(eq(hr2), eq("SKILL_REQUEST_SUBMITTED"), eq("New Skill Request"),
                eq("Jane Doe has requested a new skill: Playwright."), eq("/talent-performance?tab=settings&sub=requests"));
    }

    @Test
    void submit_fallsBackToEmailWhenRequesterHasNoEmployeeRecord() {
        UUID hr1 = UUID.randomUUID();
        when(userRepository.findByEmail("employee@test.com"))
                .thenReturn(Optional.of(User.builder().id(REQUESTER_ID).email("employee@test.com").build()));
        when(skillRequestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepo.findById(REQUESTER_ID)).thenReturn(Optional.empty());
        when(userRepository.findActiveHrAdminUserIds()).thenReturn(Set.of(hr1));

        CreateSkillRequestRequest req = new CreateSkillRequestRequest();
        req.setName("Playwright");

        service.submit(req, "employee@test.com");

        verify(notificationService).send(eq(hr1), eq("SKILL_REQUEST_SUBMITTED"), any(),
                eq("employee@test.com has requested a new skill: Playwright."), any());
    }

    @Test
    void submit_sendsNoNotificationsWhenNoActiveHrAdminExists() {
        when(userRepository.findByEmail("employee@test.com"))
                .thenReturn(Optional.of(User.builder().id(REQUESTER_ID).email("employee@test.com").build()));
        when(skillRequestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepo.findById(REQUESTER_ID)).thenReturn(Optional.of(Employee.builder().fullName("Jane Doe").build()));
        when(userRepository.findActiveHrAdminUserIds()).thenReturn(Set.of());

        CreateSkillRequestRequest req = new CreateSkillRequestRequest();
        req.setName("Playwright");

        service.submit(req, "employee@test.com");

        verifyNoInteractions(notificationService);
    }

    @Test
    void approve_createsNewSkillAndAttachesToRequesterAtLowestActiveLevel() {
        SkillRequest request = pendingRequest("Kubernetes");
        when(skillRequestRepo.findById(request.getId())).thenReturn(Optional.of(request));
        when(skillMasterRepo.save(any())).thenAnswer(inv -> {
            SkillMaster s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(employeeSkillRepo.findByEmployeeUserIdOrderByCreatedAtAsc(REQUESTER_ID)).thenReturn(List.of());
        ProficiencyLevel beginner = ProficiencyLevel.builder().id(UUID.randomUUID()).name("Beginner").sortOrder(1).build();
        ProficiencyLevel advanced = ProficiencyLevel.builder().id(UUID.randomUUID()).name("Advanced").sortOrder(3).build();
        when(proficiencyLevelRepo.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(beginner, advanced));
        stubReviewerAndNaming();

        SkillRequestResponse res = service.approve(request.getId(), "hr@test.com");

        assertEquals("Approved", res.getStatus());
        ArgumentCaptor<EmployeeSkill> captor = ArgumentCaptor.forClass(EmployeeSkill.class);
        verify(employeeSkillRepo).save(captor.capture());
        assertEquals("Kubernetes", captor.getValue().getSkillName());
        assertEquals("Beginner", captor.getValue().getProficiencyLevel(), "defaults to the lowest active level, not just the first in some arbitrary order");
        assertNotNull(captor.getValue().getSkillId());
    }

    @Test
    void mapToExisting_attachesTheExistingSkillRatherThanCreatingANewOne() {
        SkillRequest request = pendingRequest("Core Java");
        SkillMaster existing = SkillMaster.builder().id(UUID.randomUUID()).name("Java").build();
        when(skillRequestRepo.findById(request.getId())).thenReturn(Optional.of(request));
        when(skillMasterRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(employeeSkillRepo.findByEmployeeUserIdOrderByCreatedAtAsc(REQUESTER_ID)).thenReturn(List.of());
        when(proficiencyLevelRepo.findByActiveTrueOrderBySortOrderAsc())
                .thenReturn(List.of(ProficiencyLevel.builder().id(UUID.randomUUID()).name("Beginner").sortOrder(1).build()));
        stubReviewerAndNaming();

        SkillRequestResponse res = service.mapToExisting(request.getId(), existing.getId(), "hr@test.com");

        assertEquals("Mapped", res.getStatus());
        assertEquals(existing.getId(), res.getMappedToSkillId());
        verify(skillMasterRepo, never()).save(any());
    }

    @Test
    void mapToExisting_skipsAttachmentIfRequesterAlreadyHasThatSkill() {
        SkillRequest request = pendingRequest("Core Java");
        SkillMaster existing = SkillMaster.builder().id(UUID.randomUUID()).name("Java").build();
        when(skillRequestRepo.findById(request.getId())).thenReturn(Optional.of(request));
        when(skillMasterRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        EmployeeSkill alreadyHas = EmployeeSkill.builder().skillId(existing.getId()).build();
        when(employeeSkillRepo.findByEmployeeUserIdOrderByCreatedAtAsc(REQUESTER_ID)).thenReturn(List.of(alreadyHas));
        stubReviewerAndNaming();

        service.mapToExisting(request.getId(), existing.getId(), "hr@test.com");

        verify(employeeSkillRepo, never()).save(any());
    }

    @Test
    void reject_neverCreatesOrAttachesAnySkill() {
        SkillRequest request = pendingRequest("Nonsense Skill");
        when(skillRequestRepo.findById(request.getId())).thenReturn(Optional.of(request));
        stubReviewerAndNaming();

        SkillRequestResponse res = service.reject(request.getId(), "hr@test.com");

        assertEquals("Rejected", res.getStatus());
        verifyNoInteractions(skillMasterRepo);
        verify(employeeSkillRepo, never()).save(any());
    }

    @Test
    void approve_rejectsARequestThatWasAlreadyReviewed() {
        SkillRequest request = pendingRequest("Kubernetes");
        request.setStatus("Rejected");
        when(skillRequestRepo.findById(request.getId())).thenReturn(Optional.of(request));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.approve(request.getId(), "hr@test.com"));
        assertTrue(ex.getMessage().contains("already been reviewed"));
    }
}

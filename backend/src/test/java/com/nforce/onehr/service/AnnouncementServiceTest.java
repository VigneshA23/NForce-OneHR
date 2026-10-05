package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.AnnouncementResponse;
import com.nforce.onehr.dto.doc.CreateAnnouncementRequest;
import com.nforce.onehr.entity.Announcement;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AnnouncementRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * An announcement (draft or published) is actionable only by its creator; a Super Admin
 * always overrides this (e.g. to edit/delete one left behind by an HR Admin no longer
 * with the org). Drafts are visible in the list only to their creator or a Super Admin;
 * published announcements remain visible to all admins.
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock private AnnouncementRepository announcementRepo;
    @Mock private UserRepository userRepo;

    @InjectMocks
    private AnnouncementService service;

    private final UUID hrAdminAId = UUID.randomUUID();
    private final UUID hrAdminBId = UUID.randomUUID();
    private final UUID superAdminId = UUID.randomUUID();

    private User hrAdminA;
    private User hrAdminB;
    private User superAdmin;

    @BeforeEach
    void setUp() {
        hrAdminA = user(hrAdminAId, "hrA@test.com", "HR_ADMIN");
        hrAdminB = user(hrAdminBId, "hrB@test.com", "HR_ADMIN");
        superAdmin = user(superAdminId, "super@test.com", "SUPER_ADMIN");

        lenient().when(userRepo.findByEmail("hrA@test.com")).thenReturn(Optional.of(hrAdminA));
        lenient().when(userRepo.findByEmail("hrB@test.com")).thenReturn(Optional.of(hrAdminB));
        lenient().when(userRepo.findByEmail("super@test.com")).thenReturn(Optional.of(superAdmin));
    }

    private User user(UUID id, String email, String roleCode) {
        return User.builder().id(id).email(email).active(true)
                .roles(Set.of(Role.builder().id(1).code(roleCode).displayName(roleCode).build()))
                .build();
    }

    private Announcement draft(Long id, UUID createdBy) {
        return Announcement.builder().id(id).title("Draft " + id).body("body")
                .audience("All Employees").createdBy(createdBy).createdAt(Instant.now())
                .active(true).build();
    }

    private Announcement published(Long id, UUID createdBy) {
        Announcement a = draft(id, createdBy);
        a.setPublishedAt(Instant.now());
        return a;
    }

    // ── Draft visibility (list) ─────────

    @Test
    void listAll_creatorSeesOwnDraft() {
        when(announcementRepo.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(draft(1L, hrAdminAId)));

        List<AnnouncementResponse> result = service.listAll("hrA@test.com");

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void listAll_otherHrAdminCannotSeeDraft() {
        when(announcementRepo.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(draft(1L, hrAdminAId)));

        List<AnnouncementResponse> result = service.listAll("hrB@test.com");

        assertTrue(result.isEmpty());
    }

    @Test
    void listAll_superAdminSeesOthersDraft() {
        when(announcementRepo.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(draft(1L, hrAdminAId)));

        List<AnnouncementResponse> result = service.listAll("super@test.com");

        assertEquals(1, result.size());
    }

    @Test
    void listAll_publishedAnnouncementVisibleToAllAdmins() {
        when(announcementRepo.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(published(1L, hrAdminAId)));

        assertEquals(1, service.listAll("hrB@test.com").size());
        assertEquals(1, service.listAll("super@test.com").size());
    }

    // ── Direct access by id (update/publish/deactivate/reactivate/delete) ─────────

    @Test
    void update_otherHrAdminCannotAccessDraftById() {
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(draft(1L, hrAdminAId)));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.update("hrB@test.com", 1L, new com.nforce.onehr.dto.doc.UpdateAnnouncementRequest()));
    }

    @Test
    void update_superAdminCanAccessAnothersDraft() {
        Announcement a = draft(1L, hrAdminAId);
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(a));
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var req = new com.nforce.onehr.dto.doc.UpdateAnnouncementRequest();
        req.setTitle("Edited by super admin");

        AnnouncementResponse result = service.update("super@test.com", 1L, req);

        assertEquals("Edited by super admin", result.getTitle());
    }

    @Test
    void update_creatorCanEditOwnDraft() {
        Announcement a = draft(1L, hrAdminAId);
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(a));
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var req = new com.nforce.onehr.dto.doc.UpdateAnnouncementRequest();
        req.setTitle("Updated title");

        AnnouncementResponse result = service.update("hrA@test.com", 1L, req);

        assertEquals("Updated title", result.getTitle());
    }

    @Test
    void publish_otherHrAdminCannotPublishAnothersDraft() {
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(draft(1L, hrAdminAId)));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.publish("hrB@test.com", 1L));
    }

    @Test
    void publish_creatorCanPublishOwnDraft() {
        Announcement a = draft(1L, hrAdminAId);
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(a));
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnnouncementResponse result = service.publish("hrA@test.com", 1L);

        assertTrue(result.isPublished());
    }

    @Test
    void publish_afterPublishing_visibilityFollowsExistingAudienceRules() {
        Announcement a = draft(1L, hrAdminAId);
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(a));
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.publish("hrA@test.com", 1L);

        // Once published, other admins can list it (existing role-based behavior preserved).
        when(announcementRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(a));
        assertEquals(1, service.listAll("hrB@test.com").size());
        assertEquals(1, service.listAll("super@test.com").size());
    }

    @Test
    void delete_otherHrAdminCannotDeleteAnothersDraft() {
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(draft(1L, hrAdminAId)));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.delete("hrB@test.com", 1L));
        verify(announcementRepo, never()).delete(any());
    }

    @Test
    void deactivate_otherHrAdminCannotDeactivateAnothersDraft() {
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(draft(1L, hrAdminAId)));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.deactivate("hrB@test.com", 1L));
    }

    @Test
    void reactivate_otherHrAdminCannotReactivateAnothersDraft() {
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(draft(1L, hrAdminAId)));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.reactivate("hrB@test.com", 1L));
    }

    // ── Ownership after publishing ─────────

    @Test
    void update_otherHrAdminCannotEditPublishedAnnouncement() {
        Announcement a = published(1L, hrAdminAId);
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(a));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.update("hrB@test.com", 1L, new com.nforce.onehr.dto.doc.UpdateAnnouncementRequest()));
    }

    @Test
    void update_creatorCanEditOwnPublishedAnnouncement() {
        Announcement a = published(1L, hrAdminAId);
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(a));
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var req = new com.nforce.onehr.dto.doc.UpdateAnnouncementRequest();
        req.setTitle("Edited by creator");

        AnnouncementResponse result = service.update("hrA@test.com", 1L, req);

        assertEquals("Edited by creator", result.getTitle());
    }

    @Test
    void update_superAdminCanEditAnyonesPublishedAnnouncement() {
        Announcement a = published(1L, hrAdminAId);
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(a));
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var req = new com.nforce.onehr.dto.doc.UpdateAnnouncementRequest();
        req.setTitle("Edited by super admin");

        AnnouncementResponse result = service.update("super@test.com", 1L, req);

        assertEquals("Edited by super admin", result.getTitle());
    }

    @Test
    void delete_superAdminCanDeleteAnyonesPublishedAnnouncement() {
        Announcement a = published(1L, hrAdminAId);
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(a));

        service.delete("super@test.com", 1L);

        verify(announcementRepo).delete(a);
    }

    @Test
    void delete_otherHrAdminCannotDeletePublishedAnnouncement() {
        Announcement a = published(1L, hrAdminAId);
        when(announcementRepo.findById(1L)).thenReturn(Optional.of(a));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.delete("hrB@test.com", 1L));
        verify(announcementRepo, never()).delete(any());
    }

    @Test
    void create_setsCreatedByToActor() {
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreateAnnouncementRequest req = new CreateAnnouncementRequest();
        req.setTitle("New draft");
        req.setBody("body");

        AnnouncementResponse result = service.create("hrA@test.com", req);

        assertEquals(hrAdminAId, result.getCreatedBy());
        assertFalse(result.isPublished());
    }
}
//deployment fix
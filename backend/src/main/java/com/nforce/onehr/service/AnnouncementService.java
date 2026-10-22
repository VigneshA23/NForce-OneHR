package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.Announcement;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AnnouncementRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnnouncementService {

    // announcements:VIEW/CREATE/EDIT/DELETE are seeded to exactly HR_ADMIN + SUPER_ADMIN (V225) — the
    // set the old ADMIN_ROLES check named. MANAGE_ANY (V237) is seeded to SUPER_ADMIN only — the old
    // isSuperAdmin override: see others' drafts and act on another author's announcement.
    private static final String MANAGE_ANY = "announcements:CONFIGURE:ALL";

    private final AnnouncementRepository announcementRepo;
    private final UserRepository userRepo;
    private final EffectivePermissionService effectivePermissionService;

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listPublished() {
        return announcementRepo.findPublished(Instant.now()).stream()
                .map(AnnouncementResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listAll(String actorEmail) {
        User actor = requireAuthority(actorEmail, "announcements:VIEW:ALL");
        boolean manageAny = canManageAny(actor);
        return announcementRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(a -> a.getPublishedAt() != null || manageAny || actor.getId().equals(a.getCreatedBy()))
                .map(AnnouncementResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public AnnouncementResponse create(String actorEmail, CreateAnnouncementRequest req) {
        User actor = requireAuthority(actorEmail, "announcements:CREATE:ALL");
        Announcement a = Announcement.builder()
                .title(req.getTitle())
                .body(req.getBody())
                .audience(req.getAudience() != null ? req.getAudience() : "All Employees")
                .scheduledFor(req.getScheduledFor())
                .publishedAt(req.isPublishNow() ? Instant.now() : null)
                .createdBy(actor.getId())
                .build();
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse publish(String actorEmail, Long id) {
        User actor = requireAuthority(actorEmail, "announcements:EDIT:ALL");
        Announcement a = findOwned(actor, id);
        a.setPublishedAt(Instant.now());
        a.setActive(true);
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse update(String actorEmail, Long id, UpdateAnnouncementRequest req) {
        User actor = requireAuthority(actorEmail, "announcements:EDIT:ALL");
        Announcement a = findOwned(actor, id);
        if (req.getTitle() != null && !req.getTitle().isBlank()) a.setTitle(req.getTitle());
        if (req.getBody() != null && !req.getBody().isBlank()) a.setBody(req.getBody());
        if (req.getAudience() != null && !req.getAudience().isBlank()) a.setAudience(req.getAudience());
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse deactivate(String actorEmail, Long id) {
        User actor = requireAuthority(actorEmail, "announcements:EDIT:ALL");
        Announcement a = findOwned(actor, id);
        a.setActive(false);
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse reactivate(String actorEmail, Long id) {
        User actor = requireAuthority(actorEmail, "announcements:EDIT:ALL");
        Announcement a = findOwned(actor, id);
        a.setActive(true);
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public void delete(String actorEmail, Long id) {
        User actor = requireAuthority(actorEmail, "announcements:DELETE:ALL");
        Announcement a = findOwned(actor, id);
        announcementRepo.delete(a);
    }

    private User requireUser(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + email));
    }

    private User requireAuthority(String email, String authority) {
        User u = requireUser(email);
        if (!effectivePermissionService.hasAuthority(u, authority)) {
            throw new AccessDeniedException("Access denied");
        }
        return u;
    }

    private boolean canManageAny(User u) {
        return effectivePermissionService.hasAuthority(u, MANAGE_ANY);
    }

    // Only the creator may act on their own announcement (draft or published) — a holder of
    // MANAGE_ANY (Super Admin by default) overrides this, e.g. to edit/delete an announcement left behind by
    // an HR Admin no longer with the org.
    private Announcement findOwned(User actor, Long id) {
        Announcement a = announcementRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Announcement not found: " + id));
        if (!actor.getId().equals(a.getCreatedBy()) && !canManageAny(actor)) {
            throw new AccessDeniedException("Access denied");
        }
        return a;
    }
}

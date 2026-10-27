package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.Announcement;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AnnouncementRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final AnnouncementRepository announcementRepo;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listPublished() {
        return announcementRepo.findPublished(Instant.now()).stream()
                .map(AnnouncementResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listAll(String actorEmail) {
        User actor = requireAdminUser(actorEmail);
        return announcementRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(a -> a.getPublishedAt() != null || isSuperAdmin(actor) || actor.getId().equals(a.getCreatedBy()))
                .map(AnnouncementResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public AnnouncementResponse create(String actorEmail, CreateAnnouncementRequest req) {
        User actor = requireAdminUser(actorEmail);
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
        User actor = requireAdminUser(actorEmail);
        Announcement a = findOwned(actor, id);
        a.setPublishedAt(Instant.now());
        a.setActive(true);
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse update(String actorEmail, Long id, UpdateAnnouncementRequest req) {
        User actor = requireAdminUser(actorEmail);
        Announcement a = findOwned(actor, id);
        if (req.getTitle() != null && !req.getTitle().isBlank()) a.setTitle(req.getTitle());
        if (req.getBody() != null && !req.getBody().isBlank()) a.setBody(req.getBody());
        if (req.getAudience() != null && !req.getAudience().isBlank()) a.setAudience(req.getAudience());
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse deactivate(String actorEmail, Long id) {
        User actor = requireAdminUser(actorEmail);
        Announcement a = findOwned(actor, id);
        a.setActive(false);
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse reactivate(String actorEmail, Long id) {
        User actor = requireAdminUser(actorEmail);
        Announcement a = findOwned(actor, id);
        a.setActive(true);
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public void delete(String actorEmail, Long id) {
        User actor = requireAdminUser(actorEmail);
        Announcement a = findOwned(actor, id);
        announcementRepo.delete(a);
    }

    private User requireUser(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + email));
    }

    private User requireAdminUser(String email) {
        User u = requireUser(email);
        boolean isAdmin = u.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
        if (!isAdmin) {
            throw new AccessDeniedException("Access denied");
        }
        return u;
    }

    private boolean isSuperAdmin(User u) {
        return u.getRoles().stream().anyMatch(r -> "SUPER_ADMIN".equals(r.getCode()));
    }

    // Only the creator may act on their own announcement (draft or published) — a
    // Super Admin overrides this, e.g. to edit/delete an announcement left behind by
    // an HR Admin no longer with the org.
    private Announcement findOwned(User actor, Long id) {
        Announcement a = announcementRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Announcement not found: " + id));
        if (!isSuperAdmin(actor) && !actor.getId().equals(a.getCreatedBy())) {
            throw new AccessDeniedException("Access denied");
        }
        return a;
    }
}

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
        requireAdminRole(actorEmail);
        return announcementRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(AnnouncementResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public AnnouncementResponse create(String actorEmail, CreateAnnouncementRequest req) {
        User actor = requireUser(actorEmail);
        requireAdminRole(actorEmail);
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
        requireAdminRole(actorEmail);
        Announcement a = announcementRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Announcement not found: " + id));
        a.setPublishedAt(Instant.now());
        a.setActive(true);
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse update(String actorEmail, Long id, UpdateAnnouncementRequest req) {
        requireAdminRole(actorEmail);
        Announcement a = announcementRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Announcement not found: " + id));
        if (req.getTitle() != null && !req.getTitle().isBlank()) a.setTitle(req.getTitle());
        if (req.getBody() != null && !req.getBody().isBlank()) a.setBody(req.getBody());
        if (req.getAudience() != null && !req.getAudience().isBlank()) a.setAudience(req.getAudience());
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse deactivate(String actorEmail, Long id) {
        requireAdminRole(actorEmail);
        Announcement a = announcementRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Announcement not found: " + id));
        a.setActive(false);
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public AnnouncementResponse reactivate(String actorEmail, Long id) {
        requireAdminRole(actorEmail);
        Announcement a = announcementRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Announcement not found: " + id));
        a.setActive(true);
        return AnnouncementResponse.from(announcementRepo.save(a));
    }

    @Transactional
    public void delete(String actorEmail, Long id) {
        requireAdminRole(actorEmail);
        if (!announcementRepo.existsById(id)) throw new NoSuchElementException("Announcement not found: " + id);
        announcementRepo.deleteById(id);
    }

    private User requireUser(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + email));
    }

    private void requireAdminRole(String email) {
        User u = requireUser(email);
        boolean isAdmin = u.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
        if (!isAdmin) {
            throw new AccessDeniedException("Access denied");
        }
    }
}

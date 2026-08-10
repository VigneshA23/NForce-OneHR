package com.nforce.onehr.service;

import com.nforce.onehr.dto.helpcontent.CreateHelpContentRequest;
import com.nforce.onehr.dto.helpcontent.HelpContentDetailDto;
import com.nforce.onehr.dto.helpcontent.HelpContentSummaryDto;
import com.nforce.onehr.dto.helpcontent.UpdateHelpContentRequest;
import com.nforce.onehr.entity.HelpContent;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HelpContentRepository;
import com.nforce.onehr.repository.HelpContentSpecifications;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Help & Guidance content management: FAQ / Quick Help / Guide / Document. Follows
 * HelpdeskService's shape — {@code actorEmail} resolved from the JWT principal, a
 * {@code requireAdmin} guard for HR-only operations — and reuses the same
 * byte-in-Postgres attachment mechanism as {@code HelpdeskReply}/{@code EmployeeDocument}.
 * Employee-facing reads only ever see {@code published && active} rows; HR/SA reads see
 * everything (drafts and archived included) so they can manage the full catalog.
 */
@Service
@RequiredArgsConstructor
public class HelpContentService {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final HelpContentRepository repo;
    private final UserRepository userRepo;
    private final EmployeeRepository employeeRepo;

    // ── Employee-facing reads (published + active only) ─────

    @Transactional(readOnly = true)
    public Page<HelpContentSummaryDto> listPublished(String type, String category, String search, String sort, int page, int size) {
        Specification<HelpContent> spec = Specification
                .allOf(HelpContentSpecifications.publishedAndActive(),
                        HelpContentSpecifications.typeIs(type),
                        HelpContentSpecifications.categoryIs(category),
                        HelpContentSpecifications.searchText(search));
        return repo.findAll(spec, PageRequest.of(page, size, sortFor(sort))).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public HelpContentDetailDto getPublished(UUID id) {
        HelpContent content = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Content not found: " + id));
        if (!content.isPublished() || !content.isActive()) {
            throw new NoSuchElementException("Content not found: " + id);
        }
        return toDetail(content);
    }

    @Transactional
    public void trackView(UUID id) {
        // Fire-and-forget usage signal (FAQ expand, guide/document open) — no auth/ownership
        // check needed since this only ever increments a counter on already-public content.
        repo.findById(id).filter(c -> c.isPublished() && c.isActive())
                .ifPresent(c -> repo.incrementViewCount(id));
    }

    /**
     * Ranking is configurable, not a hardcoded "top 5": callers choose how many rows via
     * {@code size} and how to rank via {@code sort} — "popular" (featured, then most-viewed,
     * then manual order) or "recent" (newest first). Default is manual curation order.
     */
    private Sort sortFor(String sort) {
        if ("popular".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Order.desc("featured"), Sort.Order.desc("viewCount"), Sort.Order.asc("displayOrder"));
        }
        if ("recent".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Order.desc("createdAt"));
        }
        return Sort.by(Sort.Order.asc("displayOrder"), Sort.Order.desc("createdAt"));
    }

    // ── HR/SA admin reads (everything, incl. drafts/archived) ─

    @Transactional(readOnly = true)
    public Page<HelpContentSummaryDto> listAll(String actorEmail, String type, String category, String search, int page, int size) {
        requireAdmin(actorEmail);
        Specification<HelpContent> spec = Specification
                .allOf(HelpContentSpecifications.typeIs(type),
                        HelpContentSpecifications.categoryIs(category),
                        HelpContentSpecifications.searchText(search));
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.asc("displayOrder"), Sort.Order.desc("createdAt"))))
                .map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public HelpContentDetailDto getForAdmin(UUID id, String actorEmail) {
        requireAdmin(actorEmail);
        return toDetail(findOrThrow(id));
    }

    // ── HR/SA admin writes ────────────────────────────────────

    @Transactional
    public HelpContentDetailDto create(CreateHelpContentRequest req, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContentType type = HelpContentType.from(req.getType());

        HelpContent content = HelpContent.builder()
                .type(type.name())
                .title(req.getTitle().trim())
                .description(req.getDescription())
                .body(req.getBody())
                .category(req.getCategory())
                .featured(req.isFeatured())
                .displayOrder(req.getDisplayOrder())
                .createdBy(actor.getId())
                .build();
        content = repo.save(content);
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto update(UUID id, UpdateHelpContentRequest req, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);

        content.setTitle(req.getTitle().trim());
        content.setDescription(req.getDescription());
        content.setBody(req.getBody());
        content.setCategory(req.getCategory());
        content.setFeatured(req.isFeatured());
        content.setDisplayOrder(req.getDisplayOrder());
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto publish(UUID id, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        content.setPublished(true);
        content.setPublishedAt(Instant.now());
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto unpublish(UUID id, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        content.setPublished(false);
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto archive(UUID id, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        content.setActive(false);
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto reactivate(UUID id, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        content.setActive(true);
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    @Transactional
    public void delete(UUID id, String actorEmail) {
        requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        repo.delete(content);
    }

    @Transactional
    public HelpContentDetailDto uploadAttachment(UUID id, MultipartFile file, String actorEmail) throws IOException {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        content.setAttachmentName(file.getOriginalFilename());
        content.setAttachmentType(file.getContentType());
        content.setAttachmentSize(file.getSize());
        content.setAttachmentData(file.getBytes());
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    // ── Attachment download (published+active for everyone; admins see all) ─

    @Transactional(readOnly = true)
    public HelpContent getAttachment(UUID id, String actorEmail) {
        User actor = requireUser(actorEmail);
        HelpContent content = findOrThrow(id);
        if (content.getAttachmentData() == null) {
            throw new NoSuchElementException("This content has no attachment");
        }
        if (!isAdmin(actor) && !(content.isPublished() && content.isActive())) {
            throw new AccessDeniedException("This content is not available");
        }
        return content;
    }

    // ── Mapping ────────────────────────────────────────────────

    private HelpContentSummaryDto toSummary(HelpContent c) {
        return HelpContentSummaryDto.builder()
                .id(c.getId())
                .type(c.getType())
                .title(c.getTitle())
                .description(c.getDescription())
                .category(c.getCategory())
                .published(c.isPublished())
                .active(c.isActive())
                .featured(c.isFeatured())
                .displayOrder(c.getDisplayOrder())
                .viewCount(c.getViewCount())
                .hasAttachment(c.getAttachmentData() != null)
                .attachmentName(c.getAttachmentName())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private HelpContentDetailDto toDetail(HelpContent c) {
        return HelpContentDetailDto.builder()
                .id(c.getId())
                .type(c.getType())
                .title(c.getTitle())
                .description(c.getDescription())
                .body(c.getBody())
                .category(c.getCategory())
                .published(c.isPublished())
                .publishedAt(c.getPublishedAt())
                .active(c.isActive())
                .featured(c.isFeatured())
                .displayOrder(c.getDisplayOrder())
                .viewCount(c.getViewCount())
                .hasAttachment(c.getAttachmentData() != null)
                .attachmentName(c.getAttachmentName())
                .attachmentUrl(c.getAttachmentData() != null ? "/api/help-content/" + c.getId() + "/attachment" : null)
                .createdByName(employeeOrEmailName(c.getCreatedBy()))
                .createdAt(c.getCreatedAt())
                .updatedByName(c.getUpdatedBy() != null ? employeeOrEmailName(c.getUpdatedBy()) : null)
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private HelpContent findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("Content not found: " + id));
    }

    private User requireUser(String actorEmail) {
        return userRepo.findByEmail(actorEmail)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + actorEmail));
    }

    private User requireAdmin(String actorEmail) {
        User actor = requireUser(actorEmail);
        if (!isAdmin(actor)) {
            throw new AccessDeniedException("HR Admin or Super Admin role required");
        }
        return actor;
    }

    private boolean isAdmin(User actor) {
        return actor.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
    }

    private String employeeOrEmailName(UUID userId) {
        return employeeRepo.findById(userId).map(e -> e.getFullName())
                .orElseGet(() -> userRepo.findById(userId).map(User::getEmail).orElse("Unknown"));
    }
}

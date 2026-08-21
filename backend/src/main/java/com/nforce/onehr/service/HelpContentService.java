package com.nforce.onehr.service;

import com.nforce.onehr.dto.helpcontent.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Help & Guidance content management: FAQ / Quick Help / Guide / Document, plus the approval
 * workflow gating anything an employee can see.
 *
 * <p>Lifecycle ({@code HelpContent.status}) is the single source of truth — exactly six values:
 * DRAFT → PENDING_APPROVAL → APPROVED → PUBLISHED, with UNPUBLISHED (temporarily hidden,
 * re-publishable without a new approval if untouched) and ARCHIVED (retired, must go through
 * DRAFT → approval again to ever return) as the other two. Editing PUBLISHED content never
 * mutates the employee-visible row — {@link #prepareForEdit} forks a new DRAFT row instead, so
 * employees keep seeing the old version until the fork is approved and published.
 *
 * <p>Every submission creates an immutable {@link HelpContentApproval} attempt (text + attachment
 * snapshot) — the permanent audit trail a rejection/withdrawal/approval is recorded against,
 * never deleted. Follows HelpdeskService's shape: {@code actorEmail} resolved from the JWT
 * principal, a {@code requireAdmin} guard for HR-only (authoring) operations.
 */
@Service
@RequiredArgsConstructor
public class HelpContentService {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    // A DRAFT hasn't been through approval yet, so there's nothing worth retaining via archive —
    // Delete is the only retirement path. PENDING_APPROVAL is fully locked (withdraw first).
    private static final Set<String> ARCHIVABLE_STATUSES = Set.of("APPROVED", "PUBLISHED", "UNPUBLISHED");
    // Every status except PENDING_APPROVAL (locked pending a decision) may be permanently deleted —
    // HR/Super Admin retain full delete authority over their own content at any other stage.
    private static final Set<String> DELETABLE_STATUSES = Set.of("DRAFT", "APPROVED", "PUBLISHED", "UNPUBLISHED", "ARCHIVED");
    private static final Set<String> PUBLISHABLE_STATUSES = Set.of("APPROVED", "UNPUBLISHED");

    // Multi-attachment upload limits — configurable constants, not hardcoded checks scattered
    // through the mutation methods.
    private static final int MAX_ATTACHMENTS_PER_CONTENT = 5;
    private static final long MAX_ATTACHMENT_SIZE_BYTES = 10L * 1024 * 1024; // mirrors spring.servlet.multipart.max-file-size
    private static final Set<String> ALLOWED_ATTACHMENT_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "png", "jpg", "jpeg", "gif", "txt", "csv");

    private final HelpContentRepository repo;
    private final HelpContentAttachmentRepository attachmentRepo;
    private final HelpContentApprovalRepository approvalRepo;
    private final HelpContentApprovalAttachmentRepository approvalAttachmentRepo;
    private final EmployeeManagerHistoryRepository managerHistoryRepo;
    private final NotificationService notificationService;
    private final UserRepository userRepo;
    private final EmployeeRepository employeeRepo;

    // ── Employee-facing reads (published only) ─────

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
        if (!"PUBLISHED".equals(content.getStatus())) {
            throw new NoSuchElementException("Content not found: " + id);
        }
        return toDetail(content);
    }

    @Transactional
    public void trackView(UUID id) {
        // Fire-and-forget usage signal (FAQ expand, guide/document open) — no auth/ownership
        // check needed since this only ever increments a counter on already-public content.
        repo.findById(id).filter(c -> "PUBLISHED".equals(c.getStatus()))
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

    // ── Employee-facing attachment reads (published only for non-admins) ─

    @Transactional(readOnly = true)
    public List<AttachmentDto> listAttachments(UUID contentId, String actorEmail) {
        User actor = requireUser(actorEmail);
        HelpContent content = findOrThrow(contentId);
        requireVisible(content, actor);
        return attachmentRepo.findByContentIdOrderByDisplayOrderAsc(contentId).stream()
                .map(this::toAttachmentDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public HelpContentAttachment getAttachmentFile(UUID contentId, UUID attachmentId, String actorEmail) {
        User actor = requireUser(actorEmail);
        HelpContent content = findOrThrow(contentId);
        requireVisible(content, actor);
        return attachmentRepo.findById(attachmentId)
                .filter(a -> a.getContentId().equals(contentId))
                .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + attachmentId));
    }

    private void requireVisible(HelpContent content, User actor) {
        if (!isAdmin(actor) && !"PUBLISHED".equals(content.getStatus())) {
            throw new AccessDeniedException("This content is not available");
        }
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
        HelpContent target = prepareForEdit(findOrThrow(id), actor);

        target.setTitle(req.getTitle().trim());
        target.setDescription(req.getDescription());
        target.setBody(req.getBody());
        target.setCategory(req.getCategory());
        target.setFeatured(req.isFeatured());
        target.setDisplayOrder(req.getDisplayOrder());
        target.setUpdatedBy(actor.getId());
        repo.save(target);
        return toDetail(target);
    }

    /**
     * The single gate every content/attachment mutation goes through. DRAFT edits happen
     * in place (free editing). Editing APPROVED/UNPUBLISHED content invalidates the standing
     * approval by demoting in place to DRAFT — nothing is employee-visible in either state, so
     * no fork is needed. Editing PUBLISHED content must never touch the employee-visible row,
     * so it forks a brand-new DRAFT row (cloning current attachments) instead. PENDING_APPROVAL
     * is fully locked (withdraw first) and ARCHIVED has no edit action (restore first).
     */
    private HelpContent prepareForEdit(HelpContent content, User actor) {
        switch (content.getStatus()) {
            case "DRAFT":
                return content;
            case "APPROVED":
            case "UNPUBLISHED":
                content.setStatus("DRAFT");
                content.setRejectionReason(null);
                return repo.save(content);
            case "PUBLISHED":
                return forkDraftRevision(content, actor);
            case "PENDING_APPROVAL":
                throw new AccessDeniedException("This content is pending approval — withdraw the request before editing");
            default:
                throw new AccessDeniedException("Archived content cannot be edited — restore it first");
        }
    }

    private HelpContent forkDraftRevision(HelpContent original, User actor) {
        HelpContent revision = HelpContent.builder()
                .type(original.getType())
                .title(original.getTitle())
                .description(original.getDescription())
                .body(original.getBody())
                .category(original.getCategory())
                .featured(original.isFeatured())
                .displayOrder(original.getDisplayOrder())
                .status("DRAFT")
                .supersedesId(original.getId())
                .createdBy(original.getCreatedBy())
                .updatedBy(actor.getId())
                .build();
        revision = repo.save(revision);
        for (HelpContentAttachment src : attachmentRepo.findByContentIdOrderByDisplayOrderAsc(original.getId())) {
            attachmentRepo.save(HelpContentAttachment.builder()
                    .contentId(revision.getId())
                    .displayOrder(src.getDisplayOrder())
                    .fileName(src.getFileName())
                    .fileType(src.getFileType())
                    .fileSize(src.getFileSize())
                    .fileData(src.getFileData())
                    .checksum(src.getChecksum())
                    .createdBy(actor.getId())
                    .build());
        }
        return revision;
    }

    /**
     * Resolves an attachment id given against the pre-edit content onto its equivalent on the
     * (possibly forked) edit target — a fork clones attachments 1:1 by display order with brand
     * new ids, so a caller referencing an attachment it saw before this call started must be
     * remapped rather than looked up directly on the target.
     */
    private HelpContentAttachment remapAttachment(HelpContent original, HelpContent target, UUID attachmentId) {
        if (target.getId().equals(original.getId())) {
            return attachmentRepo.findById(attachmentId)
                    .filter(a -> a.getContentId().equals(target.getId()))
                    .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + attachmentId));
        }
        HelpContentAttachment onOriginal = attachmentRepo.findById(attachmentId)
                .filter(a -> a.getContentId().equals(original.getId()))
                .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + attachmentId));
        return attachmentRepo.findByContentIdOrderByDisplayOrderAsc(target.getId()).stream()
                .filter(a -> a.getDisplayOrder() == onOriginal.getDisplayOrder())
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Attachment not found on the new revision"));
    }

    @Transactional
    public HelpContentDetailDto addAttachment(UUID id, MultipartFile file, String actorEmail) throws IOException {
        User actor = requireAdmin(actorEmail);
        HelpContent target = prepareForEdit(findOrThrow(id), actor);
        validateAttachmentFile(file);
        long existing = attachmentRepo.countByContentId(target.getId());
        if (existing + 1 > MAX_ATTACHMENTS_PER_CONTENT) {
            throw new IllegalArgumentException("A FAQ/Guide can have at most " + MAX_ATTACHMENTS_PER_CONTENT + " attachments");
        }
        // MAX(displayOrder) + 1, not COUNT — a prior removal can leave a gap (e.g. orders 0, 2
        // after removing order 1), and COUNT would collide with a surviving attachment's order,
        // which then makes remapAttachment's order-based matching ambiguous on a later fork.
        int nextOrder = attachmentRepo.findByContentIdOrderByDisplayOrderAsc(target.getId()).stream()
                .mapToInt(HelpContentAttachment::getDisplayOrder).max().orElse(-1) + 1;
        byte[] data = file.getBytes();
        attachmentRepo.save(HelpContentAttachment.builder()
                .contentId(target.getId())
                .displayOrder(nextOrder)
                .fileName(file.getOriginalFilename())
                .fileType(file.getContentType())
                .fileSize(file.getSize())
                .fileData(data)
                .checksum(sha256Hex(data))
                .createdBy(actor.getId())
                .build());
        target.setUpdatedBy(actor.getId());
        repo.save(target);
        return toDetail(target);
    }

    /**
     * Multi-file upload in one action — fetches/forks the edit target exactly once (unlike
     * calling {@link #addAttachment} in a loop, which would fork a PUBLISHED row again on every
     * file), then validates the whole batch against the attachment-count limit before writing
     * any of it.
     */
    @Transactional
    public HelpContentDetailDto addAttachments(UUID id, List<MultipartFile> files, String actorEmail) throws IOException {
        User actor = requireAdmin(actorEmail);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one file is required");
        }
        HelpContent target = prepareForEdit(findOrThrow(id), actor);
        long existing = attachmentRepo.countByContentId(target.getId());
        if (existing + files.size() > MAX_ATTACHMENTS_PER_CONTENT) {
            throw new IllegalArgumentException("A FAQ/Guide can have at most " + MAX_ATTACHMENTS_PER_CONTENT
                    + " attachments (currently " + existing + ", tried to add " + files.size() + ")");
        }
        for (MultipartFile file : files) {
            validateAttachmentFile(file);
        }
        int nextOrder = attachmentRepo.findByContentIdOrderByDisplayOrderAsc(target.getId()).stream()
                .mapToInt(HelpContentAttachment::getDisplayOrder).max().orElse(-1) + 1;
        for (MultipartFile file : files) {
            byte[] data = file.getBytes();
            attachmentRepo.save(HelpContentAttachment.builder()
                    .contentId(target.getId())
                    .displayOrder(nextOrder++)
                    .fileName(file.getOriginalFilename())
                    .fileType(file.getContentType())
                    .fileSize(file.getSize())
                    .fileData(data)
                    .checksum(sha256Hex(data))
                    .createdBy(actor.getId())
                    .build());
        }
        target.setUpdatedBy(actor.getId());
        repo.save(target);
        return toDetail(target);
    }

    private void validateAttachmentFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Attachment file is empty");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE_BYTES) {
            throw new IllegalArgumentException("\"" + file.getOriginalFilename() + "\" exceeds the "
                    + (MAX_ATTACHMENT_SIZE_BYTES / (1024 * 1024)) + "MB attachment size limit");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_ATTACHMENT_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type: ." + ext
                    + " (allowed: " + String.join(", ", ALLOWED_ATTACHMENT_EXTENSIONS) + ")");
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    @Transactional
    public HelpContentDetailDto removeAttachment(UUID id, UUID attachmentId, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent original = findOrThrow(id);
        HelpContent target = prepareForEdit(original, actor);
        HelpContentAttachment attachment = remapAttachment(original, target, attachmentId);
        attachmentRepo.delete(attachment);
        target.setUpdatedBy(actor.getId());
        repo.save(target);
        return toDetail(target);
    }

    @Transactional
    public HelpContentDetailDto replaceAttachment(UUID id, UUID attachmentId, MultipartFile file, String actorEmail) throws IOException {
        User actor = requireAdmin(actorEmail);
        HelpContent original = findOrThrow(id);
        HelpContent target = prepareForEdit(original, actor);
        HelpContentAttachment attachment = remapAttachment(original, target, attachmentId);
        validateAttachmentFile(file);
        byte[] data = file.getBytes();
        attachment.setFileName(file.getOriginalFilename());
        attachment.setFileType(file.getContentType());
        attachment.setFileSize(file.getSize());
        attachment.setFileData(data);
        attachment.setChecksum(sha256Hex(data));
        attachmentRepo.save(attachment);
        target.setUpdatedBy(actor.getId());
        repo.save(target);
        return toDetail(target);
    }

    @Transactional
    public HelpContentDetailDto reorderAttachments(UUID id, ReorderAttachmentsRequest req, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent original = findOrThrow(id);
        HelpContent target = prepareForEdit(original, actor);
        List<UUID> remapped = req.getAttachmentIds().stream()
                .map(aid -> remapAttachment(original, target, aid).getId())
                .collect(Collectors.toList());

        List<HelpContentAttachment> current = attachmentRepo.findByContentIdOrderByDisplayOrderAsc(target.getId());
        if (current.size() != remapped.size() || !current.stream().map(HelpContentAttachment::getId).collect(Collectors.toSet()).equals(new HashSet<>(remapped))) {
            throw new IllegalArgumentException("Attachment order must include exactly the content's current attachments");
        }
        Map<UUID, HelpContentAttachment> byId = current.stream().collect(Collectors.toMap(HelpContentAttachment::getId, a -> a));
        for (int i = 0; i < remapped.size(); i++) {
            HelpContentAttachment a = byId.get(remapped.get(i));
            a.setDisplayOrder(i);
            attachmentRepo.save(a);
        }
        target.setUpdatedBy(actor.getId());
        repo.save(target);
        return toDetail(target);
    }

    // ── Approval workflow ──────────────────────────────────────

    @Transactional
    public HelpContentDetailDto submit(UUID id, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        if (!"DRAFT".equals(content.getStatus())) {
            throw new AccessDeniedException("Only draft content can be submitted for approval");
        }
        UUID approverId = resolveApprover(content.getCreatedBy());
        int attemptNumber = (int) approvalRepo.countByContentId(id) + 1;

        HelpContentApproval attempt = HelpContentApproval.builder()
                .contentId(id)
                .attemptNumber(attemptNumber)
                .submittedBy(actor.getId())
                .approverId(approverId)
                .status("PENDING")
                .snapshotTitle(content.getTitle())
                .snapshotDescription(content.getDescription())
                .snapshotBody(content.getBody())
                .snapshotCategory(content.getCategory())
                .snapshotFeatured(content.isFeatured())
                .snapshotDisplayOrder(content.getDisplayOrder())
                .build();
        attempt = approvalRepo.save(attempt);

        for (HelpContentAttachment a : attachmentRepo.findByContentIdOrderByDisplayOrderAsc(id)) {
            approvalAttachmentRepo.save(HelpContentApprovalAttachment.builder()
                    .approvalId(attempt.getId())
                    .displayOrder(a.getDisplayOrder())
                    .fileName(a.getFileName())
                    .fileType(a.getFileType())
                    .fileSize(a.getFileSize())
                    .fileData(a.getFileData())
                    .checksum(a.getChecksum())
                    .build());
        }

        content.setStatus("PENDING_APPROVAL");
        content.setUpdatedBy(actor.getId());
        repo.save(content);

        notificationService.send(approverId, "HELP_CONTENT_SUBMITTED",
                "New content pending your approval",
                employeeOrEmailName(actor.getId()) + " submitted \"" + content.getTitle() + "\" for approval",
                "/approvals?type=HELP_CONTENT");
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto withdraw(UUID id, WithdrawRequest req, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        if (!"PENDING_APPROVAL".equals(content.getStatus())) {
            throw new AccessDeniedException("Only content pending approval can be withdrawn");
        }
        if (!actor.getId().equals(content.getCreatedBy()) && !isSuperAdmin(actor)) {
            throw new AccessDeniedException("Only the author can withdraw this request");
        }
        HelpContentApproval attempt = approvalRepo.findByContentIdAndStatus(id, "PENDING")
                .orElseThrow(() -> new NoSuchElementException("No pending approval attempt found for " + id));
        attempt.setStatus("WITHDRAWN");
        attempt.setDecidedAt(Instant.now());
        attempt.setWithdrawalReason(req.getReason());
        approvalRepo.save(attempt);

        content.setStatus("DRAFT");
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto approveAttempt(UUID attemptId, String actorEmail) {
        User actor = requireUser(actorEmail);
        HelpContentApproval attempt = findAttemptOrThrow(attemptId);
        assertIsApprover(attempt, actor);
        if (!"PENDING".equals(attempt.getStatus())) {
            throw new AccessDeniedException("This approval request has already been decided");
        }
        attempt.setStatus("APPROVED");
        attempt.setDecidedAt(Instant.now());
        approvalRepo.save(attempt);

        HelpContent content = findOrThrow(attempt.getContentId());
        content.setStatus("APPROVED");
        content.setRejectionReason(null);
        repo.save(content);

        notificationService.send(content.getCreatedBy(), "HELP_CONTENT_APPROVED",
                "Content approved",
                "\"" + content.getTitle() + "\" was approved and is ready to publish",
                "/help");
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto rejectAttempt(UUID attemptId, RejectRequest req, String actorEmail) {
        User actor = requireUser(actorEmail);
        HelpContentApproval attempt = findAttemptOrThrow(attemptId);
        assertIsApprover(attempt, actor);
        if (!"PENDING".equals(attempt.getStatus())) {
            throw new AccessDeniedException("This approval request has already been decided");
        }
        attempt.setStatus("REJECTED");
        attempt.setDecidedAt(Instant.now());
        attempt.setRejectionReason(req.getReason());
        approvalRepo.save(attempt);

        HelpContent content = findOrThrow(attempt.getContentId());
        content.setStatus("DRAFT");
        content.setRejectionReason(req.getReason());
        repo.save(content);

        notificationService.send(content.getCreatedBy(), "HELP_CONTENT_REJECTED",
                "Content rejected",
                "\"" + content.getTitle() + "\" was rejected: " + req.getReason(),
                "/help");
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto publish(UUID id, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        if (!PUBLISHABLE_STATUSES.contains(content.getStatus())) {
            throw new AccessDeniedException("Only approved or unpublished content can be published");
        }
        content.setStatus("PUBLISHED");
        content.setPublishedAt(Instant.now());
        content.setUpdatedBy(actor.getId());
        repo.save(content);

        if (content.getSupersedesId() != null) {
            repo.findById(content.getSupersedesId()).ifPresent(old -> {
                old.setStatus("ARCHIVED");
                old.setUpdatedBy(actor.getId());
                repo.save(old);
            });
        }
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto unpublish(UUID id, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        if (!"PUBLISHED".equals(content.getStatus())) {
            throw new AccessDeniedException("Only published content can be unpublished");
        }
        content.setStatus("UNPUBLISHED");
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    @Transactional
    public HelpContentDetailDto archive(UUID id, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        if (!ARCHIVABLE_STATUSES.contains(content.getStatus())) {
            throw new AccessDeniedException("Content in status " + content.getStatus() + " cannot be archived");
        }
        content.setStatus("ARCHIVED");
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    /** Restore always returns to DRAFT — never straight to PUBLISHED; a fresh approval is required. */
    @Transactional
    public HelpContentDetailDto restore(UUID id, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        if (!"ARCHIVED".equals(content.getStatus())) {
            throw new AccessDeniedException("Only archived content can be restored");
        }
        content.setStatus("DRAFT");
        content.setRejectionReason(null);
        content.setUpdatedBy(actor.getId());
        repo.save(content);
        return toDetail(content);
    }

    @Transactional
    public void delete(UUID id, String actorEmail) {
        requireAdmin(actorEmail);
        HelpContent content = findOrThrow(id);
        if (!DELETABLE_STATUSES.contains(content.getStatus())) {
            throw new AccessDeniedException("Only draft or archived content can be deleted");
        }
        attachmentRepo.deleteByContentId(id);
        repo.delete(content);
    }

    // ── Approver resolution ─────────────────────────────────────

    /**
     * Walks the reporting hierarchy from the content author upward (cycle-guarded, same
     * pattern as {@code OrgHierarchyService.buildBreadcrumb}), returning the first active
     * manager found. Falls back to the earliest-created active Super Admin if the chain is
     * empty or every manager in it is inactive/deleted — submission is never blocked for lack
     * of an available manager.
     */
    private UUID resolveApprover(UUID authorUserId) {
        Set<UUID> visited = new HashSet<>();
        UUID current = authorUserId;
        while (current != null && visited.add(current)) {
            UUID managerId = managerHistoryRepo.findByEmployeeUserIdAndEffectiveToIsNull(current)
                    .map(EmployeeManagerHistory::getManagerUserId)
                    .orElse(null);
            if (managerId == null) break;
            User candidate = userRepo.findById(managerId).orElse(null);
            if (candidate != null && candidate.isActive() && candidate.getDeletedAt() == null) {
                return managerId;
            }
            current = managerId;
        }
        List<User> superAdmins = userRepo.findActiveSuperAdmins();
        if (superAdmins.isEmpty()) {
            throw new IllegalStateException("No active Super Admin available as fallback approver");
        }
        return superAdmins.get(0).getId();
    }

    private void assertIsApprover(HelpContentApproval attempt, User actor) {
        // Author cannot decide their own submission — checked first so a Super Admin who
        // authored (and submitted) this attempt themselves doesn't slip through the blanket
        // fallback-authority bypass below.
        if (actor.getId().equals(attempt.getSubmittedBy())) {
            throw new AccessDeniedException("You cannot approve or reject your own submission");
        }
        if (actor.getId().equals(attempt.getApproverId())) return;
        if (isSuperAdmin(actor)) return;
        throw new AccessDeniedException("You are not authorized to decide this approval request");
    }

    private boolean isSuperAdmin(User actor) {
        return actor.getRoles().stream().anyMatch(r -> "SUPER_ADMIN".equals(r.getCode()));
    }

    // ── Approval Center ──────────────────────────────────────────

    /** SUPER_ADMIN sees every PENDING attempt (blanket fallback-authority visibility, same
     * convention as every other approval-capable service in this codebase); everyone else sees
     * only attempts resolved to them. */
    @Transactional(readOnly = true)
    public List<ApprovalAttemptDto> listPendingApprovalsForApprover(String actorEmail) {
        User actor = requireUser(actorEmail);
        List<HelpContentApproval> attempts = isSuperAdmin(actor)
                ? approvalRepo.findByStatus("PENDING")
                : approvalRepo.findByApproverIdAndStatus(actor.getId(), "PENDING");
        return attempts.stream().map(this::toAttemptDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ApprovalAttemptDto getAttempt(UUID attemptId, String actorEmail) {
        User actor = requireUser(actorEmail);
        HelpContentApproval attempt = findAttemptOrThrow(attemptId);
        assertCanViewAttempt(attempt, actor);
        return toAttemptDto(attempt);
    }

    @Transactional(readOnly = true)
    public HelpContentApprovalAttachment getApprovalAttachmentFile(UUID attemptId, UUID attachmentId, String actorEmail) {
        User actor = requireUser(actorEmail);
        HelpContentApproval attempt = findAttemptOrThrow(attemptId);
        assertCanViewAttempt(attempt, actor);
        return approvalAttachmentRepo.findById(attachmentId)
                .filter(a -> a.getApprovalId().equals(attemptId))
                .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + attachmentId));
    }

    @Transactional(readOnly = true)
    public ApprovalDiffDto getAttemptDiff(UUID attemptId, String actorEmail) {
        User actor = requireUser(actorEmail);
        HelpContentApproval current = findAttemptOrThrow(attemptId);
        assertCanViewAttempt(current, actor);
        Optional<HelpContentApproval> previousOpt = approvalRepo.findByContentIdAndAttemptNumber(current.getContentId(), current.getAttemptNumber() - 1);

        List<FieldChangeDto> fieldChanges = List.of(
                diffField("title", previousOpt.map(HelpContentApproval::getSnapshotTitle).orElse(null), current.getSnapshotTitle()),
                diffField("description", previousOpt.map(HelpContentApproval::getSnapshotDescription).orElse(null), current.getSnapshotDescription()),
                diffField("body", previousOpt.map(HelpContentApproval::getSnapshotBody).orElse(null), current.getSnapshotBody()),
                diffField("category", previousOpt.map(HelpContentApproval::getSnapshotCategory).orElse(null), current.getSnapshotCategory())
        );

        List<HelpContentApprovalAttachment> previousAttachments = previousOpt
                .map(p -> approvalAttachmentRepo.findByApprovalIdOrderByDisplayOrderAsc(p.getId()))
                .orElse(List.of());
        List<HelpContentApprovalAttachment> currentAttachments = approvalAttachmentRepo.findByApprovalIdOrderByDisplayOrderAsc(current.getId());
        List<AttachmentChangeDto> attachmentChanges = diffAttachments(previousAttachments, currentAttachments);

        boolean modified = previousOpt.isEmpty()
                || fieldChanges.stream().anyMatch(FieldChangeDto::isChanged)
                || attachmentChanges.stream().anyMatch(c -> !"UNCHANGED".equals(c.getChangeType()));

        return ApprovalDiffDto.builder()
                .previous(previousOpt.map(this::toAttemptDto).orElse(null))
                .current(toAttemptDto(current))
                .modified(modified)
                .fieldChanges(fieldChanges)
                .attachmentChanges(attachmentChanges)
                .build();
    }

    private void assertCanViewAttempt(HelpContentApproval attempt, User actor) {
        if (isAdmin(actor)) return; // HR/SA authors can view their own content's approval history
        if (actor.getId().equals(attempt.getApproverId())) return;
        throw new AccessDeniedException("You are not authorized to view this approval request");
    }

    private HelpContentApproval findAttemptOrThrow(UUID attemptId) {
        return approvalRepo.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("Approval attempt not found: " + attemptId));
    }

    private boolean isModified(HelpContentApproval previous, HelpContentApproval current) {
        if (!Objects.equals(previous.getSnapshotTitle(), current.getSnapshotTitle())) return true;
        if (!Objects.equals(previous.getSnapshotDescription(), current.getSnapshotDescription())) return true;
        if (!Objects.equals(previous.getSnapshotBody(), current.getSnapshotBody())) return true;
        if (!Objects.equals(previous.getSnapshotCategory(), current.getSnapshotCategory())) return true;
        if (previous.isSnapshotFeatured() != current.isSnapshotFeatured()) return true;
        if (previous.getSnapshotDisplayOrder() != current.getSnapshotDisplayOrder()) return true;
        List<HelpContentApprovalAttachment> prevAtt = approvalAttachmentRepo.findByApprovalIdOrderByDisplayOrderAsc(previous.getId());
        List<HelpContentApprovalAttachment> curAtt = approvalAttachmentRepo.findByApprovalIdOrderByDisplayOrderAsc(current.getId());
        if (prevAtt.size() != curAtt.size()) return true;
        for (int i = 0; i < prevAtt.size(); i++) {
            if (!prevAtt.get(i).getChecksum().equals(curAtt.get(i).getChecksum())) return true;
            if (!Objects.equals(prevAtt.get(i).getFileName(), curAtt.get(i).getFileName())) return true;
        }
        return false;
    }

    // ── Text/attachment comparison (for the Approval Center's "View Changes") ─

    private FieldChangeDto diffField(String name, String oldValue, String newValue) {
        boolean changed = !Objects.equals(oldValue, newValue);
        return FieldChangeDto.builder()
                .fieldName(name)
                .changed(changed)
                .oldValue(oldValue)
                .newValue(newValue)
                .segments(changed ? wordDiff(oldValue, newValue) : List.of())
                .build();
    }

    /** Word-level LCS diff — even a single-character change surfaces as an ADDED/REMOVED segment. */
    private static List<DiffSegmentDto> wordDiff(String oldText, String newText) {
        String oldT = oldText == null ? "" : oldText;
        String newT = newText == null ? "" : newText;
        if (oldT.equals(newT)) {
            return oldT.isEmpty() ? List.of() : List.of(new DiffSegmentDto("EQUAL", oldT));
        }
        String[] a = oldT.isEmpty() ? new String[0] : oldT.split("(?<=\\s)|(?=\\s)");
        String[] b = newT.isEmpty() ? new String[0] : newT.split("(?<=\\s)|(?=\\s)");
        // Guard against pathological O(n*m) on very large bodies — fall back to a coarse diff.
        if ((long) a.length * b.length > 200_000) {
            List<DiffSegmentDto> coarse = new ArrayList<>();
            if (!oldT.isEmpty()) coarse.add(new DiffSegmentDto("REMOVED", oldT));
            if (!newT.isEmpty()) coarse.add(new DiffSegmentDto("ADDED", newT));
            return coarse;
        }
        int n = a.length, m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<DiffSegmentDto> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentType = null;
        int i = 0, j = 0;
        while (i < n || j < m) {
            String type;
            String token;
            if (i < n && j < m && a[i].equals(b[j])) {
                type = "EQUAL"; token = a[i]; i++; j++;
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                type = "ADDED"; token = b[j]; j++;
            } else {
                type = "REMOVED"; token = a[i]; i++;
            }
            if (!type.equals(currentType)) {
                if (currentType != null) segments.add(new DiffSegmentDto(currentType, current.toString()));
                currentType = type;
                current = new StringBuilder();
            }
            current.append(token);
        }
        if (currentType != null) segments.add(new DiffSegmentDto(currentType, current.toString()));
        return segments;
    }

    /**
     * Matches attachments between two attempts by checksum first (content identity survives
     * remove-then-re-add or reordering); leftover unmatched pairs by position are REPLACED,
     * and anything still unmatched is a pure ADDED/REMOVED.
     */
    private List<AttachmentChangeDto> diffAttachments(List<HelpContentApprovalAttachment> previous, List<HelpContentApprovalAttachment> current) {
        List<AttachmentChangeDto> changes = new ArrayList<>();
        List<HelpContentApprovalAttachment> remainingPrev = new ArrayList<>(previous);
        List<HelpContentApprovalAttachment> unmatchedCurrent = new ArrayList<>();

        for (HelpContentApprovalAttachment cur : current) {
            HelpContentApprovalAttachment match = remainingPrev.stream()
                    .filter(p -> p.getChecksum().equals(cur.getChecksum()))
                    .findFirst().orElse(null);
            if (match != null) {
                remainingPrev.remove(match);
                boolean unchanged = match.getDisplayOrder() == cur.getDisplayOrder() && Objects.equals(match.getFileName(), cur.getFileName());
                changes.add(AttachmentChangeDto.builder()
                        .changeType(unchanged ? "UNCHANGED" : "REORDERED")
                        .fileName(cur.getFileName()).previousFileName(match.getFileName())
                        .displayOrder(cur.getDisplayOrder()).previousDisplayOrder(match.getDisplayOrder())
                        .build());
            } else {
                unmatchedCurrent.add(cur);
            }
        }

        int pairs = Math.min(remainingPrev.size(), unmatchedCurrent.size());
        for (int i = 0; i < pairs; i++) {
            HelpContentApprovalAttachment old = remainingPrev.get(i);
            HelpContentApprovalAttachment cur = unmatchedCurrent.get(i);
            changes.add(AttachmentChangeDto.builder()
                    .changeType("REPLACED")
                    .fileName(cur.getFileName()).previousFileName(old.getFileName())
                    .displayOrder(cur.getDisplayOrder()).previousDisplayOrder(old.getDisplayOrder())
                    .build());
        }
        for (int i = pairs; i < remainingPrev.size(); i++) {
            HelpContentApprovalAttachment old = remainingPrev.get(i);
            changes.add(AttachmentChangeDto.builder()
                    .changeType("REMOVED")
                    .previousFileName(old.getFileName()).previousDisplayOrder(old.getDisplayOrder())
                    .build());
        }
        for (int i = pairs; i < unmatchedCurrent.size(); i++) {
            HelpContentApprovalAttachment cur = unmatchedCurrent.get(i);
            changes.add(AttachmentChangeDto.builder()
                    .changeType("ADDED")
                    .fileName(cur.getFileName()).displayOrder(cur.getDisplayOrder())
                    .build());
        }
        return changes;
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Mapping ────────────────────────────────────────────────

    private HelpContentSummaryDto toSummary(HelpContent c) {
        return HelpContentSummaryDto.builder()
                .id(c.getId())
                .type(c.getType())
                .title(c.getTitle())
                .description(c.getDescription())
                .category(c.getCategory())
                .status(c.getStatus())
                .featured(c.isFeatured())
                .displayOrder(c.getDisplayOrder())
                .viewCount(c.getViewCount())
                .attachmentCount((int) attachmentRepo.countByContentId(c.getId()))
                .rejectionReason(c.getRejectionReason())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private HelpContentDetailDto toDetail(HelpContent c) {
        List<AttachmentDto> attachments = attachmentRepo.findByContentIdOrderByDisplayOrderAsc(c.getId())
                .stream().map(this::toAttachmentDto).collect(Collectors.toList());
        return HelpContentDetailDto.builder()
                .id(c.getId())
                .type(c.getType())
                .title(c.getTitle())
                .description(c.getDescription())
                .body(c.getBody())
                .category(c.getCategory())
                .status(c.getStatus())
                .publishedAt(c.getPublishedAt())
                .featured(c.isFeatured())
                .displayOrder(c.getDisplayOrder())
                .viewCount(c.getViewCount())
                .attachments(attachments)
                .rejectionReason(c.getRejectionReason())
                .createdByName(employeeOrEmailName(c.getCreatedBy()))
                .createdAt(c.getCreatedAt())
                .updatedByName(c.getUpdatedBy() != null ? employeeOrEmailName(c.getUpdatedBy()) : null)
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private ApprovalAttemptDto toAttemptDto(HelpContentApproval attempt) {
        HelpContent content = repo.findById(attempt.getContentId()).orElse(null);
        List<AttachmentDto> attachments = approvalAttachmentRepo.findByApprovalIdOrderByDisplayOrderAsc(attempt.getId())
                .stream().map(this::toAttachmentDto).collect(Collectors.toList());
        boolean modifiedSincePrevious = approvalRepo.findByContentIdAndAttemptNumber(attempt.getContentId(), attempt.getAttemptNumber() - 1)
                .map(prev -> isModified(prev, attempt)).orElse(false);
        return ApprovalAttemptDto.builder()
                .id(attempt.getId())
                .contentId(attempt.getContentId())
                .contentType(content != null ? content.getType() : null)
                .contentTitle(attempt.getSnapshotTitle())
                .attemptNumber(attempt.getAttemptNumber())
                .submittedByUserId(attempt.getSubmittedBy())
                .submittedByName(employeeOrEmailName(attempt.getSubmittedBy()))
                .submittedAt(attempt.getSubmittedAt())
                .approverName(employeeOrEmailName(attempt.getApproverId()))
                .status(attempt.getStatus())
                .decidedAt(attempt.getDecidedAt())
                .rejectionReason(attempt.getRejectionReason())
                .withdrawalReason(attempt.getWithdrawalReason())
                .snapshotTitle(attempt.getSnapshotTitle())
                .snapshotDescription(attempt.getSnapshotDescription())
                .snapshotBody(attempt.getSnapshotBody())
                .snapshotCategory(attempt.getSnapshotCategory())
                .snapshotFeatured(attempt.isSnapshotFeatured())
                .snapshotDisplayOrder(attempt.getSnapshotDisplayOrder())
                .attachments(attachments)
                .modifiedSincePrevious(modifiedSincePrevious)
                .build();
    }

    private AttachmentDto toAttachmentDto(HelpContentAttachment a) {
        return AttachmentDto.builder().id(a.getId()).fileName(a.getFileName()).fileType(a.getFileType()).fileSize(a.getFileSize()).displayOrder(a.getDisplayOrder()).build();
    }

    private AttachmentDto toAttachmentDto(HelpContentApprovalAttachment a) {
        return AttachmentDto.builder().id(a.getId()).fileName(a.getFileName()).fileType(a.getFileType()).fileSize(a.getFileSize()).displayOrder(a.getDisplayOrder()).build();
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

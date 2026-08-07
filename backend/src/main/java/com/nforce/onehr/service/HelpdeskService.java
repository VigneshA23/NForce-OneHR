package com.nforce.onehr.service;

import com.nforce.onehr.dto.helpdesk.*;
import com.nforce.onehr.entity.HelpdeskCategory;
import com.nforce.onehr.entity.HelpdeskReply;
import com.nforce.onehr.entity.HelpdeskTicket;
import com.nforce.onehr.entity.User;
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
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HR Help Desk: employee support requests as trackable tickets instead of emails.
 * Follows OnboardingService's shape exactly — {@code actorEmail} resolved from the JWT
 * principal on every call, a {@code requireAdmin} guard for HR-only operations, and
 * NotificationService called directly at each state transition. Employee identity is
 * always derived from the authenticated principal — never from a request payload.
 */
@Service
@RequiredArgsConstructor
public class HelpdeskService {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final String TICKET_NUMBER_PREFIX = "HR";
    private static final String LINK_EMPLOYEE_HELP = "/help";
    private static final String LINK_ADMIN_QUEUE = "/requests";

    private final HelpdeskTicketRepository ticketRepo;
    private final HelpdeskReplyRepository replyRepo;
    private final HelpdeskCategoryRepository categoryRepo;
    private final UserRepository userRepo;
    private final EmployeeRepository employeeRepo;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    // ── Category master (dropdown) ─────────────────────────

    @Transactional(readOnly = true)
    public List<HelpdeskCategoryResponse> listActiveCategories() {
        return categoryRepo.findByActiveTrueOrderByNameAsc().stream()
                .map(HelpdeskCategoryResponse::from)
                .collect(Collectors.toList());
    }

    // ── Create (employee) ──────────────────────────────────

    @Transactional
    public TicketDetailDto createTicket(CreateHelpdeskTicketRequest req, String actorEmail) {
        User employee = requireUser(actorEmail);
        HelpdeskCategory category = categoryRepo.findById(req.getCategoryId())
                .filter(HelpdeskCategory::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or inactive topic: " + req.getCategoryId()));

        HelpdeskTicket ticket = HelpdeskTicket.builder()
                .ticketNumber(generateTicketNumber())
                .employeeUserId(employee.getId())
                .category(category)
                .description(req.getDescription().trim())
                .status(TicketStatus.OPEN.name())
                .priority(TicketPriority.MEDIUM.name())
                .build();
        ticket = ticketRepo.save(ticket);

        auditService.log(employee.getId(), "HELPDESK_TICKET_CREATED", ticket.getId());

        for (UUID hrUserId : userRepo.findAdminUserIds()) {
            notificationService.send(hrUserId, "HELPDESK_TICKET_CREATED",
                    "New Help Desk ticket",
                    "Ticket " + ticket.getTicketNumber() + " (" + category.getName() + ") raised by "
                            + employeeOrEmailName(employee.getId()),
                    LINK_ADMIN_QUEUE);
        }

        return toDetail(ticket, false);
    }

    // ── List: employee's own tickets ───────────────────────

    @Transactional(readOnly = true)
    public Page<TicketSummaryDto> listMine(String actorEmail, String status, String search, int page, int size) {
        User employee = requireUser(actorEmail);
        Specification<HelpdeskTicket> spec = Specification
                .allOf(HelpdeskTicketSpecifications.employeeIs(employee.getId()),
                        HelpdeskTicketSpecifications.statusIs(status),
                        HelpdeskTicketSpecifications.searchText(search));
        return ticketRepo.findAll(spec, PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(this::toSummary);
    }

    // ── List: HR queue (all tickets) ───────────────────────

    @Transactional(readOnly = true)
    public Page<TicketSummaryDto> listQueue(String actorEmail, String status, UUID assignedTo, String search, int page, int size) {
        requireAdmin(actorEmail);
        Specification<HelpdeskTicket> spec = Specification
                .allOf(HelpdeskTicketSpecifications.statusIs(status),
                        HelpdeskTicketSpecifications.assignedTo(assignedTo),
                        HelpdeskTicketSpecifications.searchText(search));
        return ticketRepo.findAll(spec, PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(this::toSummary);
    }

    // ── Detail (owner-or-admin) ─────────────────────────────

    @Transactional(readOnly = true)
    public TicketDetailDto getDetail(UUID ticketId, String actorEmail) {
        User actor = requireUser(actorEmail);
        boolean isAdmin = isAdmin(actor);
        HelpdeskTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new NoSuchElementException("Ticket not found: " + ticketId));
        if (!isAdmin && !ticket.getEmployeeUserId().equals(actor.getId())) {
            throw new AccessDeniedException("You may only view your own tickets");
        }
        return toDetail(ticket, !isAdmin);
    }

    // ── Reply (owner employee or any HR admin) ─────────────

    @Transactional
    public ReplyDto addReply(UUID ticketId, String message, boolean internalRequested,
                              MultipartFile attachment, String actorEmail) throws IOException {
        User actor = requireUser(actorEmail);
        boolean isAdmin = isAdmin(actor);
        HelpdeskTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new NoSuchElementException("Ticket not found: " + ticketId));

        if (!isAdmin) {
            if (!ticket.getEmployeeUserId().equals(actor.getId())) {
                throw new AccessDeniedException("You may only reply to your own tickets");
            }
        }
        if (TicketStatus.CLOSED.name().equals(ticket.getStatus())) {
            throw new IllegalStateException("This ticket is closed and can no longer receive replies");
        }

        // Internal notes are HR-only, regardless of what the client sends.
        boolean internal = isAdmin && internalRequested;

        HelpdeskReply.HelpdeskReplyBuilder replyBuilder = HelpdeskReply.builder()
                .ticketId(ticket.getId())
                .senderId(actor.getId())
                .senderRole(isAdmin ? SenderRole.HR.name() : SenderRole.EMPLOYEE.name())
                .message(message.trim())
                .internal(internal);
        applyAttachment(replyBuilder, attachment);
        HelpdeskReply reply = replyRepo.save(replyBuilder.build());

        // The employee replying while HR is waiting on them puts the ball back in HR's court.
        if (!isAdmin && TicketStatus.WAITING_FOR_EMPLOYEE.name().equals(ticket.getStatus())) {
            ticket.setStatus(TicketStatus.IN_PROGRESS.name());
            ticketRepo.save(ticket);
        }

        auditService.log(actor.getId(), "HELPDESK_TICKET_REPLIED", ticket.getId());

        if (isAdmin) {
            if (!internal) {
                notificationService.send(ticket.getEmployeeUserId(), "HELPDESK_TICKET_REPLIED",
                        "HR replied to your ticket",
                        "New reply on ticket " + ticket.getTicketNumber(), LINK_EMPLOYEE_HELP);
            }
        } else if (ticket.getAssignedTo() != null) {
            notificationService.send(ticket.getAssignedTo(), "HELPDESK_TICKET_REPLIED",
                    "Employee replied to their ticket",
                    "New reply on ticket " + ticket.getTicketNumber(), LINK_ADMIN_QUEUE);
        } else {
            for (UUID hrUserId : userRepo.findAdminUserIds()) {
                notificationService.send(hrUserId, "HELPDESK_TICKET_REPLIED",
                        "Employee replied on an unassigned ticket",
                        "New reply on ticket " + ticket.getTicketNumber(), LINK_ADMIN_QUEUE);
            }
        }

        return toReplyDto(reply);
    }

    // ── Status change (HR only) ─────────────────────────────

    @Transactional
    public TicketDetailDto updateStatus(UUID ticketId, UpdateTicketStatusRequest req, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpdeskTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new NoSuchElementException("Ticket not found: " + ticketId));

        TicketStatus current = TicketStatus.from(ticket.getStatus());
        TicketStatus target = TicketStatus.from(req.getStatus());
        if (current == target) {
            return toDetail(ticket, false);
        }
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException("Cannot move a ticket from " + current + " to " + target);
        }

        ticket.setStatus(target.name());
        if (target == TicketStatus.RESOLVED) {
            ticket.setResolvedAt(Instant.now());
            ticket.setResolvedBy(actor.getId());
        }
        ticketRepo.save(ticket);

        if (req.getComment() != null && !req.getComment().isBlank()) {
            replyRepo.save(HelpdeskReply.builder()
                    .ticketId(ticket.getId())
                    .senderId(actor.getId())
                    .senderRole(SenderRole.HR.name())
                    .message(req.getComment().trim())
                    .internal(false)
                    .build());
        }

        // audit_log.before_state/after_state are JSON columns — must serialize via
        // AuditSnapshotSerializer (see LeaveService's approve/reject), not pass raw strings;
        // Postgres rejects a bare unquoted string as invalid JSON.
        String before = auditSnapshot.toJson(Map.of("status", current.name()));
        String after = auditSnapshot.toJson(Map.of("status", target.name()));
        auditService.log(actor.getId(), "HELPDESK_TICKET_STATUS_CHANGED", ticket.getId(), before, after);
        notificationService.send(ticket.getEmployeeUserId(), "HELPDESK_TICKET_STATUS_CHANGED",
                target == TicketStatus.RESOLVED ? "Your ticket was resolved" : "Your ticket status changed",
                "Ticket " + ticket.getTicketNumber() + " is now " + target.name().replace('_', ' '),
                LINK_EMPLOYEE_HELP);

        return toDetail(ticket, false);
    }

    // ── Assign (HR only) ─────────────────────────────────────

    @Transactional
    public TicketDetailDto assignTicket(UUID ticketId, AssignTicketRequest req, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        HelpdeskTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new NoSuchElementException("Ticket not found: " + ticketId));

        if (!userRepo.findAdminUserIds().contains(req.getAssigneeUserId())) {
            throw new IllegalArgumentException("Assignee must be an HR Admin or Super Admin");
        }

        ticket.setAssignedTo(req.getAssigneeUserId());
        if (TicketStatus.OPEN.name().equals(ticket.getStatus())) {
            ticket.setStatus(TicketStatus.ASSIGNED.name());
        }
        ticketRepo.save(ticket);

        // Audit records who performed the assignment (the acting HR admin), not who was
        // assigned — the notification below is correctly the other way around (assignee only).
        auditService.log(actor.getId(), "HELPDESK_TICKET_ASSIGNED", ticket.getId());
        notificationService.send(req.getAssigneeUserId(), "HELPDESK_TICKET_ASSIGNED",
                "Ticket assigned to you",
                "Ticket " + ticket.getTicketNumber() + " has been assigned to you", LINK_ADMIN_QUEUE);

        return toDetail(ticket, false);
    }

    // ── Dashboard (HR only) ──────────────────────────────────

    @Transactional(readOnly = true)
    public HelpdeskDashboardDto getDashboard(String actorEmail) {
        requireAdmin(actorEmail);
        return HelpdeskDashboardDto.builder()
                .openCount(ticketRepo.countByStatus(TicketStatus.OPEN.name()))
                .assignedCount(ticketRepo.countByStatus(TicketStatus.ASSIGNED.name()))
                .inProgressCount(ticketRepo.countByStatus(TicketStatus.IN_PROGRESS.name()))
                .waitingForEmployeeCount(ticketRepo.countByStatus(TicketStatus.WAITING_FOR_EMPLOYEE.name()))
                .resolvedCount(ticketRepo.countByStatus(TicketStatus.RESOLVED.name()))
                .closedCount(ticketRepo.countByStatus(TicketStatus.CLOSED.name()))
                .build();
    }

    @Transactional(readOnly = true)
    public List<AssignableAgentDto> listAssignableAgents(String actorEmail) {
        requireAdmin(actorEmail);
        return userRepo.findAdminUserIds().stream()
                .map(id -> AssignableAgentDto.builder().userId(id).name(employeeOrEmailName(id)).build())
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .collect(Collectors.toList());
    }

    // ── Attachment download (owner-or-admin) ────────────────

    @Transactional(readOnly = true)
    public HelpdeskReply getReplyAttachment(UUID replyId, String actorEmail) {
        User actor = requireUser(actorEmail);
        HelpdeskReply reply = replyRepo.findById(replyId)
                .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + replyId));
        if (reply.getAttachmentData() == null) {
            throw new NoSuchElementException("This reply has no attachment");
        }
        HelpdeskTicket ticket = ticketRepo.findById(reply.getTicketId())
                .orElseThrow(() -> new NoSuchElementException("Ticket not found: " + reply.getTicketId()));
        if (!isAdmin(actor) && !ticket.getEmployeeUserId().equals(actor.getId())) {
            throw new AccessDeniedException("You may not access this attachment");
        }
        return reply;
    }

    // ── Mapping ──────────────────────────────────────────────

    private TicketSummaryDto toSummary(HelpdeskTicket t) {
        return TicketSummaryDto.builder()
                .id(t.getId())
                .ticketNumber(t.getTicketNumber())
                .categoryName(t.getCategory().getName())
                .status(t.getStatus())
                .priority(t.getPriority())
                .employeeUserId(t.getEmployeeUserId())
                .employeeName(employeeOrEmailName(t.getEmployeeUserId()))
                .assignedTo(t.getAssignedTo())
                .assignedToName(t.getAssignedTo() != null ? employeeOrEmailName(t.getAssignedTo()) : null)
                .replyCount(replyRepo.countByTicketId(t.getId()))
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    private TicketDetailDto toDetail(HelpdeskTicket t, boolean hideInternal) {
        List<ReplyDto> replies = replyRepo.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                .filter(r -> !hideInternal || !r.isInternal())
                .map(this::toReplyDto)
                .collect(Collectors.toList());

        return TicketDetailDto.builder()
                .id(t.getId())
                .ticketNumber(t.getTicketNumber())
                .categoryId(t.getCategory().getId())
                .categoryName(t.getCategory().getName())
                .description(t.getDescription())
                .status(t.getStatus())
                .priority(t.getPriority())
                .employeeUserId(t.getEmployeeUserId())
                .employeeName(employeeOrEmailName(t.getEmployeeUserId()))
                .assignedTo(t.getAssignedTo())
                .assignedToName(t.getAssignedTo() != null ? employeeOrEmailName(t.getAssignedTo()) : null)
                .resolvedAt(t.getResolvedAt())
                .resolvedByName(t.getResolvedBy() != null ? employeeOrEmailName(t.getResolvedBy()) : null)
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .replies(replies)
                .build();
    }

    private ReplyDto toReplyDto(HelpdeskReply r) {
        return ReplyDto.builder()
                .id(r.getId())
                .senderId(r.getSenderId())
                .senderName(employeeOrEmailName(r.getSenderId()))
                .senderRole(r.getSenderRole())
                .message(r.getMessage())
                .internal(r.isInternal())
                .hasAttachment(r.getAttachmentData() != null)
                .attachmentName(r.getAttachmentName())
                .attachmentUrl(r.getAttachmentData() != null ? "/api/helpdesk/replies/" + r.getId() + "/attachment" : null)
                .createdAt(r.getCreatedAt())
                .build();
    }

    private void applyAttachment(HelpdeskReply.HelpdeskReplyBuilder builder, MultipartFile attachment) throws IOException {
        if (attachment == null || attachment.isEmpty()) return;
        builder.attachmentName(attachment.getOriginalFilename())
                .attachmentType(attachment.getContentType())
                .attachmentSize(attachment.getSize())
                .attachmentData(attachment.getBytes());
    }

    private String generateTicketNumber() {
        long seq = ticketRepo.nextTicketSequence();
        return TICKET_NUMBER_PREFIX + "-" + Year.now() + "-" + String.format("%06d", seq);
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

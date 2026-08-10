package com.nforce.onehr.controller;

import com.nforce.onehr.dto.helpdesk.*;
import com.nforce.onehr.service.HelpdeskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * HR Admin Help Desk queue — every endpoint here is admin-only, so the class-level
 * {@code @PreAuthorize} (same precedent as OnboardingController/AuditLogController) covers
 * the whole controller rather than repeating it per method.
 */
@RestController
@RequestMapping("/api/hr/helpdesk")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class HrHelpdeskController {

    private final HelpdeskService helpdeskService;

    @GetMapping
    public Page<TicketSummaryDto> queue(
            // Accepts either a single status (?status=OPEN) or several (?status=OPEN&status=IN_PROGRESS,
            // or a comma-joined ?status=OPEN,IN_PROGRESS) — the "Active Queue" default filter needs
            // OPEN + IN_PROGRESS together; a single value behaves exactly as before.
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Principal principal) {
        return helpdeskService.listQueue(principal.getName(), status, assignedTo, search, page, size);
    }

    @GetMapping("/dashboard")
    public HelpdeskDashboardDto dashboard(Principal principal) {
        return helpdeskService.getDashboard(principal.getName());
    }

    @GetMapping("/agents")
    public List<AssignableAgentDto> agents(Principal principal) {
        return helpdeskService.listAssignableAgents(principal.getName());
    }

    @GetMapping("/{id}")
    public TicketDetailDto getOne(@PathVariable UUID id, Principal principal) {
        return helpdeskService.getDetail(id, principal.getName());
    }

    @PostMapping("/{id}/start-working")
    public TicketDetailDto startWorking(@PathVariable UUID id, Principal principal) {
        return helpdeskService.startWorking(id, principal.getName());
    }

    @PutMapping("/{id}/status")
    public TicketDetailDto updateStatus(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateTicketStatusRequest req,
                                         Principal principal) {
        return helpdeskService.updateStatus(id, req, principal.getName());
    }

    @PutMapping("/{id}/assign")
    public TicketDetailDto assign(@PathVariable UUID id,
                                   @Valid @RequestBody AssignTicketRequest req,
                                   Principal principal) {
        return helpdeskService.assignTicket(id, req, principal.getName());
    }

    @PostMapping(value = "/{id}/reply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReplyDto reply(
            @PathVariable UUID id,
            @RequestParam String message,
            @RequestParam(defaultValue = "false") boolean internal,
            @RequestParam(required = false) MultipartFile attachment,
            Principal principal) throws IOException {
        return helpdeskService.addReply(id, message, internal, attachment, principal.getName());
    }
}

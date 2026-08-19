package com.nforce.onehr.controller;

import com.nforce.onehr.dto.helpdesk.*;
import com.nforce.onehr.entity.HelpdeskReply;
import com.nforce.onehr.service.HelpdeskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Employee-facing Help Desk surface — raise a ticket, track and reply to your own tickets.
 * Identity always comes from {@code Principal}, never a request payload (see HelpdeskService).
 * Ownership/role checks happen in the service, matching DocumentController's blended pattern,
 * since {@code /{id}}, {@code /{id}/reply} and the attachment download are also reachable by HR.
 */
@RestController
@RequestMapping("/api/helpdesk")
@RequiredArgsConstructor
public class HelpdeskController {

    private final HelpdeskService helpdeskService;

    @GetMapping("/categories")
    public List<HelpdeskCategoryResponse> categories() {
        return helpdeskService.listActiveCategories();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetailDto create(@Valid @RequestBody CreateHelpdeskTicketRequest req, Principal principal) {
        return helpdeskService.createTicket(req, principal.getName());
    }

    @GetMapping("/my")
    public Page<TicketSummaryDto> myTickets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Principal principal) {
        return helpdeskService.listMine(principal.getName(), status, search, page, size);
    }

    @GetMapping("/{id}")
    public TicketDetailDto getOne(@PathVariable UUID id, Principal principal) {
        return helpdeskService.getDetail(id, principal.getName());
    }

    @PostMapping(value = "/{id}/reply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReplyDto reply(
            @PathVariable UUID id,
            @RequestParam String message,
            @RequestParam(required = false) MultipartFile attachment,
            Principal principal) throws IOException {
        // internal notes are HR-only; an employee can never set this — enforced again in the service
        return helpdeskService.addReply(id, message, false, attachment, principal.getName());
    }

    @PostMapping("/{id}/close")
    public TicketDetailDto close(@PathVariable UUID id, Principal principal) {
        return helpdeskService.closeTicket(id, principal.getName());
    }

    @GetMapping("/replies/{replyId}/attachment")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID replyId, Principal principal) {
        HelpdeskReply reply = helpdeskService.getReplyAttachment(replyId, principal.getName());
        MediaType mediaType = reply.getAttachmentType() != null
                ? MediaType.parseMediaType(reply.getAttachmentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition cd = ContentDisposition.attachment().filename(reply.getAttachmentName()).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(mediaType)
                .body(reply.getAttachmentData());
    }
}

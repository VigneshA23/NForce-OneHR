package com.nforce.onehr.controller;

import com.nforce.onehr.dto.helpcontent.CreateHelpContentRequest;
import com.nforce.onehr.dto.helpcontent.HelpContentDetailDto;
import com.nforce.onehr.dto.helpcontent.HelpContentSummaryDto;
import com.nforce.onehr.dto.helpcontent.PublishRequest;
import com.nforce.onehr.dto.helpcontent.ReorderAttachmentsRequest;
import com.nforce.onehr.dto.helpcontent.UpdateHelpContentRequest;
import com.nforce.onehr.dto.helpcontent.WithdrawRequest;
import com.nforce.onehr.service.HelpContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * HR Admin content-management surface — every endpoint here is admin-only, so the class-level
 * {@code @PreAuthorize} (same precedent as {@code HrHelpdeskController}) covers the whole
 * controller rather than repeating it per method. Author-side lifecycle actions only
 * (create/edit/submit/withdraw/publish/archive/restore/delete) — approve/reject live on the
 * employee-facing {@code HelpContentController} instead, since the resolved approver is often a
 * plain MANAGER who wouldn't pass this controller's role check.
 */
@RestController
@RequestMapping("/api/hr/help-content")
@RequiredArgsConstructor
public class HrHelpContentController {

    private final HelpContentService service;

    @GetMapping
    @PreAuthorize("hasAuthority('hr_help_content:VIEW:ALL')")
    public Page<HelpContentSummaryDto> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal) {
        return service.listAll(principal.getName(), type, category, search, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('hr_help_content:VIEW:ALL')")
    public HelpContentDetailDto getOne(@PathVariable UUID id, Principal principal) {
        return service.getForAdmin(id, principal.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr_help_content:CREATE:ALL')")
    public HelpContentDetailDto create(@Valid @RequestBody CreateHelpContentRequest req, Principal principal) {
        return service.create(req, principal.getName());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto update(@PathVariable UUID id, @Valid @RequestBody UpdateHelpContentRequest req, Principal principal) {
        return service.update(id, req, principal.getName());
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto submit(@PathVariable UUID id, Principal principal) {
        return service.submit(id, principal.getName());
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto withdraw(@PathVariable UUID id, @Valid @RequestBody WithdrawRequest req, Principal principal) {
        return service.withdraw(id, req, principal.getName());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto publish(@PathVariable UUID id, @RequestBody PublishRequest req, Principal principal) {
        return service.publish(id, req, principal.getName());
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto unpublish(@PathVariable UUID id, Principal principal) {
        return service.unpublish(id, principal.getName());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto archive(@PathVariable UUID id, Principal principal) {
        return service.archive(id, principal.getName());
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto restore(@PathVariable UUID id, Principal principal) {
        return service.restore(id, principal.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('hr_help_content:DELETE:ALL')")
    public void delete(@PathVariable UUID id, Principal principal) {
        service.delete(id, principal.getName());
    }

    // ── Attachments (multiple, ordered) — each mutation participates in approval exactly like
    //    an edit does: if the target content is APPROVED/UNPUBLISHED it demotes to DRAFT, if
    //    PUBLISHED it forks a new draft revision, and the returned id reflects that. ──

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto addAttachment(@PathVariable UUID id, @RequestParam MultipartFile file, Principal principal) throws IOException {
        return service.addAttachment(id, file, principal.getName());
    }

    /** Multiple files selected/uploaded in one action — see {@code HelpContentService#addAttachments}. */
    @PostMapping(value = "/{id}/attachments/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto addAttachments(@PathVariable UUID id, @RequestParam("files") List<MultipartFile> files, Principal principal) throws IOException {
        return service.addAttachments(id, files, principal.getName());
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto removeAttachment(@PathVariable UUID id, @PathVariable UUID attachmentId, Principal principal) {
        return service.removeAttachment(id, attachmentId, principal.getName());
    }

    @PutMapping(value = "/{id}/attachments/{attachmentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto replaceAttachment(@PathVariable UUID id, @PathVariable UUID attachmentId, @RequestParam MultipartFile file, Principal principal) throws IOException {
        return service.replaceAttachment(id, attachmentId, file, principal.getName());
    }

    @PatchMapping("/{id}/attachments/order")
    @PreAuthorize("hasAuthority('hr_help_content:EDIT:ALL')")
    public HelpContentDetailDto reorderAttachments(@PathVariable UUID id, @Valid @RequestBody ReorderAttachmentsRequest req, Principal principal) {
        return service.reorderAttachments(id, req, principal.getName());
    }
}

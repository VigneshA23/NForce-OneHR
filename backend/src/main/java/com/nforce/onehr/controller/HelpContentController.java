package com.nforce.onehr.controller;

import com.nforce.onehr.dto.helpcontent.ApprovalAttemptDto;
import com.nforce.onehr.dto.helpcontent.ApprovalDiffDto;
import com.nforce.onehr.dto.helpcontent.AttachmentDto;
import com.nforce.onehr.dto.helpcontent.HelpContentDetailDto;
import com.nforce.onehr.dto.helpcontent.HelpContentSummaryDto;
import com.nforce.onehr.dto.helpcontent.RejectRequest;
import com.nforce.onehr.entity.HelpContentAttachment;
import com.nforce.onehr.entity.HelpContentApprovalAttachment;
import com.nforce.onehr.service.HelpContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Employee-facing Help & Guidance content surface — search/browse published FAQs, Quick Help,
 * Guides and Documents, plus (deliberately, no class-level role guard) the approval
 * decision endpoints. The resolved approver for a piece of content is often a plain MANAGER,
 * not HR_ADMIN/SUPER_ADMIN, so approve/reject can't live behind {@code HrHelpContentController}'s
 * admin-only guard — same pattern as {@code LeaveController}/{@code AssetController}, whose
 * approve/reject endpoints are likewise open to any authenticated user with the service layer
 * checking "are you the resolved approver".
 */
@RestController
@RequestMapping("/api/help-content")
@RequiredArgsConstructor
public class HelpContentController {

    private final HelpContentService service;

    @GetMapping
    public Page<HelpContentSummaryDto> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listPublished(type, category, search, sort, page, size);
    }

    @GetMapping("/{id}")
    public HelpContentDetailDto getOne(@PathVariable UUID id) {
        return service.getPublished(id);
    }

    @PostMapping("/{id}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trackView(@PathVariable UUID id) {
        service.trackView(id);
    }

    @GetMapping("/{id}/attachments")
    public List<AttachmentDto> listAttachments(@PathVariable UUID id, Authentication authentication) {
        return service.listAttachments(id, authentication.getName());
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID id, @PathVariable UUID attachmentId, Authentication authentication) {
        HelpContentAttachment attachment = service.getAttachmentFile(id, attachmentId, authentication.getName());
        return fileResponse(attachment.getFileName(), attachment.getFileData());
    }

    @GetMapping("/approvals/{attemptId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadApprovalAttachment(@PathVariable UUID attemptId, @PathVariable UUID attachmentId, Authentication authentication) {
        HelpContentApprovalAttachment attachment = service.getApprovalAttachmentFile(attemptId, attachmentId, authentication.getName());
        return fileResponse(attachment.getFileName(), attachment.getFileData());
    }

    private ResponseEntity<byte[]> fileResponse(String fileName, byte[] data) {
        String ext = extensionOf(fileName);
        MediaType mediaType = switch (ext) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        ContentDisposition cd = (mediaType == MediaType.APPLICATION_OCTET_STREAM)
                ? ContentDisposition.attachment().filename(fileName).build()
                : ContentDisposition.inline().filename(fileName).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(mediaType)
                .body(data);
    }

    // ── Approval decisions — see class javadoc for why these live here, not on HrHelpContentController ──

    @GetMapping("/approvals/{attemptId}")
    public ApprovalAttemptDto getAttempt(@PathVariable UUID attemptId, Authentication authentication) {
        return service.getAttempt(attemptId, authentication.getName());
    }

    @GetMapping("/approvals/{attemptId}/diff")
    public ApprovalDiffDto getAttemptDiff(@PathVariable UUID attemptId, Authentication authentication) {
        return service.getAttemptDiff(attemptId, authentication.getName());
    }

    @PostMapping("/approvals/{attemptId}/approve")
    public HelpContentDetailDto approve(@PathVariable UUID attemptId, Authentication authentication) {
        return service.approveAttempt(attemptId, authentication.getName());
    }

    @PostMapping("/approvals/{attemptId}/reject")
    public HelpContentDetailDto reject(@PathVariable UUID attemptId, @Valid @RequestBody RejectRequest req, Authentication authentication) {
        return service.rejectAttempt(attemptId, req, authentication.getName());
    }

    private String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}

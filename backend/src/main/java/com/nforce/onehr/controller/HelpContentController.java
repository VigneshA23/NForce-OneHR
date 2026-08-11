package com.nforce.onehr.controller;

import com.nforce.onehr.dto.helpcontent.HelpContentDetailDto;
import com.nforce.onehr.dto.helpcontent.HelpContentSummaryDto;
import com.nforce.onehr.entity.HelpContent;
import com.nforce.onehr.service.HelpContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Employee-facing Help & Guidance content surface — search/browse published FAQs, Quick Help,
 * Guides and Documents. Read-only: no method here can create/edit/publish/delete anything.
 * Any authenticated user may call these (no role guard), same pattern as
 * {@code HelpdeskController.categories()} and {@code DocumentTypeController.active()}.
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

    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID id, Authentication authentication) {
        HelpContent content = service.getAttachment(id, authentication.getName());
        String ext = extensionOf(content.getAttachmentName());
        MediaType mediaType = switch (ext) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        ContentDisposition cd = (mediaType == MediaType.APPLICATION_OCTET_STREAM)
                ? ContentDisposition.attachment().filename(content.getAttachmentName()).build()
                : ContentDisposition.inline().filename(content.getAttachmentName()).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(mediaType)
                .body(content.getAttachmentData());
    }

    private String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}

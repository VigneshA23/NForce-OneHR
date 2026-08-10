package com.nforce.onehr.controller;

import com.nforce.onehr.dto.helpcontent.CreateHelpContentRequest;
import com.nforce.onehr.dto.helpcontent.HelpContentDetailDto;
import com.nforce.onehr.dto.helpcontent.HelpContentSummaryDto;
import com.nforce.onehr.dto.helpcontent.UpdateHelpContentRequest;
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
import java.util.UUID;

/**
 * HR Admin content-management surface — every endpoint here is admin-only, so the class-level
 * {@code @PreAuthorize} (same precedent as {@code HrHelpdeskController}) covers the whole
 * controller rather than repeating it per method.
 */
@RestController
@RequestMapping("/api/hr/help-content")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class HrHelpContentController {

    private final HelpContentService service;

    @GetMapping
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
    public HelpContentDetailDto getOne(@PathVariable UUID id, Principal principal) {
        return service.getForAdmin(id, principal.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HelpContentDetailDto create(@Valid @RequestBody CreateHelpContentRequest req, Principal principal) {
        return service.create(req, principal.getName());
    }

    @PatchMapping("/{id}")
    public HelpContentDetailDto update(@PathVariable UUID id, @Valid @RequestBody UpdateHelpContentRequest req, Principal principal) {
        return service.update(id, req, principal.getName());
    }

    @PostMapping("/{id}/publish")
    public HelpContentDetailDto publish(@PathVariable UUID id, Principal principal) {
        return service.publish(id, principal.getName());
    }

    @PostMapping("/{id}/unpublish")
    public HelpContentDetailDto unpublish(@PathVariable UUID id, Principal principal) {
        return service.unpublish(id, principal.getName());
    }

    @PostMapping("/{id}/archive")
    public HelpContentDetailDto archive(@PathVariable UUID id, Principal principal) {
        return service.archive(id, principal.getName());
    }

    @PostMapping("/{id}/reactivate")
    public HelpContentDetailDto reactivate(@PathVariable UUID id, Principal principal) {
        return service.reactivate(id, principal.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Principal principal) {
        service.delete(id, principal.getName());
    }

    @PostMapping(value = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HelpContentDetailDto uploadAttachment(@PathVariable UUID id, @RequestParam MultipartFile file, Principal principal) throws IOException {
        return service.uploadAttachment(id, file, principal.getName());
    }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.service.DocumentTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/doc-types")
@RequiredArgsConstructor
public class DocumentTypeController {

    private final DocumentTypeService service;

    @GetMapping("/active")
    public List<DocumentTypeResponse> listActive() {
        return service.listActive();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<DocumentTypeResponse> listAll() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public DocumentTypeResponse create(@Valid @RequestBody CreateDocumentTypeRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public DocumentTypeResponse update(@PathVariable Integer id, @Valid @RequestBody UpdateDocumentTypeRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/toggle-active")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void toggleActive(@PathVariable Integer id) {
        service.toggleActive(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void delete(@PathVariable Integer id) {
        service.delete(id);
    }
}

package com.nforce.onehr.controller.talent;

import com.nforce.onehr.dto.talent.CertificateCategoryResponse;
import com.nforce.onehr.dto.talent.UpsertCertificateCategoryRequest;
import com.nforce.onehr.service.talent.CertificateCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/org/talent/certificate-categories")
@RequiredArgsConstructor
public class CertificateCategoryController {

    private final CertificateCategoryService service;

    // No role restriction — every authenticated employee needs this for their own Certificates tab.
    @GetMapping("/active")
    public List<CertificateCategoryResponse> listActive() {
        return service.listActive();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<CertificateCategoryResponse> listAll() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public CertificateCategoryResponse create(@Valid @RequestBody UpsertCertificateCategoryRequest req, Authentication auth) {
        return service.create(req, auth.getName());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public CertificateCategoryResponse update(@PathVariable UUID id, @Valid @RequestBody UpsertCertificateCategoryRequest req, Authentication auth) {
        return service.update(id, req, auth.getName());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void deactivate(@PathVariable UUID id, Authentication auth) {
        service.setActive(id, false, auth.getName());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void reactivate(@PathVariable UUID id, Authentication auth) {
        service.setActive(id, true, auth.getName());
    }
}

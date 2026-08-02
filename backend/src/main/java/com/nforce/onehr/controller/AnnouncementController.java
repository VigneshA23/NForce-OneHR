package com.nforce.onehr.controller;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.service.AnnouncementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService service;

    @GetMapping("/published")
    public List<AnnouncementResponse> published() {
        return service.listPublished();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<AnnouncementResponse> listAll(Principal principal) {
        return service.listAll(principal.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public AnnouncementResponse create(Principal principal, @Valid @RequestBody CreateAnnouncementRequest req) {
        return service.create(principal.getName(), req);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public AnnouncementResponse publish(Principal principal, @PathVariable Long id) {
        return service.publish(principal.getName(), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public AnnouncementResponse update(Principal principal, @PathVariable Long id,
                                       @Valid @RequestBody UpdateAnnouncementRequest req) {
        return service.update(principal.getName(), id, req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public AnnouncementResponse deactivate(Principal principal, @PathVariable Long id) {
        return service.deactivate(principal.getName(), id);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public AnnouncementResponse reactivate(Principal principal, @PathVariable Long id) {
        return service.reactivate(principal.getName(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void delete(Principal principal, @PathVariable Long id) {
        service.delete(principal.getName(), id);
    }
}

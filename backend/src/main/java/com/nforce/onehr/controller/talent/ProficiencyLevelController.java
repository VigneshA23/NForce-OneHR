package com.nforce.onehr.controller.talent;

import com.nforce.onehr.dto.talent.ProficiencyLevelResponse;
import com.nforce.onehr.dto.talent.ReorderProficiencyLevelsRequest;
import com.nforce.onehr.dto.talent.UpsertProficiencyLevelRequest;
import com.nforce.onehr.service.talent.ProficiencyLevelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/org/talent/proficiency-levels")
@RequiredArgsConstructor
public class ProficiencyLevelController {

    private final ProficiencyLevelService service;

    // No role restriction — every authenticated employee needs this for their own Skills tab.
    @GetMapping("/active")
    public List<ProficiencyLevelResponse> listActive() {
        return service.listActive();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public List<ProficiencyLevelResponse> listAll() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public ProficiencyLevelResponse create(@Valid @RequestBody UpsertProficiencyLevelRequest req, Authentication auth) {
        return service.create(req, auth.getName());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public ProficiencyLevelResponse rename(@PathVariable UUID id, @Valid @RequestBody UpsertProficiencyLevelRequest req, Authentication auth) {
        return service.rename(id, req, auth.getName());
    }

    @PostMapping("/reorder")
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public List<ProficiencyLevelResponse> reorder(@Valid @RequestBody ReorderProficiencyLevelsRequest req) {
        return service.reorder(req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public void deactivate(@PathVariable UUID id, Authentication auth) {
        service.deactivate(id, auth.getName());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public void reactivate(@PathVariable UUID id, Authentication auth) {
        service.reactivate(id, auth.getName());
    }
}

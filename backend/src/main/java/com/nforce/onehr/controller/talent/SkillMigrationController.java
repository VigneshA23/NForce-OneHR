package com.nforce.onehr.controller.talent;

import com.nforce.onehr.dto.talent.MigrationScanSummary;
import com.nforce.onehr.dto.talent.ResolveMigrationCandidateRequest;
import com.nforce.onehr.dto.talent.SkillMigrationCandidateResponse;
import com.nforce.onehr.service.talent.SkillMigrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Settings > Migration Review (org.talent.configure) — dead from the UI (the tab was removed),
 *  backend left intact. */
@RestController
@RequestMapping("/api/org/talent/migration")
@RequiredArgsConstructor
public class SkillMigrationController {

    private final SkillMigrationService service;

    @PostMapping("/scan")
    @PreAuthorize("hasAuthority('talent_skills:CREATE:ALL')")
    public MigrationScanSummary scan() {
        return service.scan();
    }

    @GetMapping("/candidates/pending")
    @PreAuthorize("hasAuthority('talent_skills:VIEW:ALL')")
    public List<SkillMigrationCandidateResponse> listPending() {
        return service.listPending();
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAuthority('talent_skills:VIEW:ALL')")
    public List<SkillMigrationCandidateResponse> listAll() {
        return service.listAll();
    }

    @PostMapping("/candidates/{id}/resolve")
    @PreAuthorize("hasAuthority('talent_skills:APPROVE:ALL')")
    public SkillMigrationCandidateResponse resolve(@PathVariable UUID id,
                                                    @Valid @RequestBody ResolveMigrationCandidateRequest req,
                                                    Authentication auth) {
        return service.resolve(id, req, auth.getName());
    }

    @PostMapping("/candidates/{id}/reopen")
    @PreAuthorize("hasAuthority('talent_skills:APPROVE:ALL')")
    public SkillMigrationCandidateResponse reopen(@PathVariable UUID id, Authentication auth) {
        return service.reopen(id, auth.getName());
    }
}

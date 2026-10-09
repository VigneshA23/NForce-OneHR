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

/** Settings > Migration Review (org.talent.configure). */
@RestController
@RequestMapping("/api/org/talent/migration")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class SkillMigrationController {

    private final SkillMigrationService service;

    @PostMapping("/scan")
    public MigrationScanSummary scan() {
        return service.scan();
    }

    @GetMapping("/candidates/pending")
    public List<SkillMigrationCandidateResponse> listPending() {
        return service.listPending();
    }

    @GetMapping("/candidates")
    public List<SkillMigrationCandidateResponse> listAll() {
        return service.listAll();
    }

    @PostMapping("/candidates/{id}/resolve")
    public SkillMigrationCandidateResponse resolve(@PathVariable UUID id,
                                                    @Valid @RequestBody ResolveMigrationCandidateRequest req,
                                                    Authentication auth) {
        return service.resolve(id, req, auth.getName());
    }

    @PostMapping("/candidates/{id}/reopen")
    public SkillMigrationCandidateResponse reopen(@PathVariable UUID id, Authentication auth) {
        return service.reopen(id, auth.getName());
    }
}

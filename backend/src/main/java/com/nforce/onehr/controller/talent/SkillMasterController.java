package com.nforce.onehr.controller.talent;

import com.nforce.onehr.dto.talent.CreateSkillMasterRequest;
import com.nforce.onehr.dto.talent.MergeSkillsRequest;
import com.nforce.onehr.dto.talent.SkillMasterResponse;
import com.nforce.onehr.dto.talent.UpdateSkillMasterRequest;
import com.nforce.onehr.service.talent.SkillMasterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settings > Skills master list. No restriction (listActive, used by the employee Skills tab's
 * own autocomplete — see the spec's "ONE shared backend API; scope decided by role" note, though
 * here there is no manager/HR split at all, since the master list itself is the same for
 * everyone) vs {@code hasAuthority('talent_skills:CONFIGURE:ALL')} for everything else (master-list
 * CRUD/merge/alias — see V225's seed-matrix header for why these collapse to one action).
 */
@RestController
@RequestMapping("/api/org/talent/skills")
@RequiredArgsConstructor
public class SkillMasterController {

    private final SkillMasterService service;

    // No role restriction — every authenticated employee needs this for their own Skills tab's
    // autocomplete, same exposure level as GET /api/doc-types/active.
    @GetMapping("/active")
    public List<SkillMasterResponse> listActive() {
        return service.listActive();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public List<SkillMasterResponse> listAll() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public SkillMasterResponse create(@Valid @RequestBody CreateSkillMasterRequest req, Authentication auth) {
        return service.create(req, auth.getName());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public SkillMasterResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSkillMasterRequest req, Authentication auth) {
        return service.update(id, req, auth.getName());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public void deactivate(@PathVariable UUID id, Authentication auth) {
        service.setActive(id, false, auth.getName());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public void reactivate(@PathVariable UUID id, Authentication auth) {
        service.setActive(id, true, auth.getName());
    }

    @PostMapping("/{id}/aliases")
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public SkillMasterResponse addAlias(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return service.addAlias(id, body.get("alias"));
    }

    @DeleteMapping("/{id}/aliases/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public void removeAlias(@PathVariable UUID id, @PathVariable UUID aliasId) {
        service.removeAlias(id, aliasId);
    }

    @PostMapping("/merge")
    @PreAuthorize("hasAuthority('talent_skills:CONFIGURE:ALL')")
    public SkillMasterResponse merge(@Valid @RequestBody MergeSkillsRequest req, Authentication auth) {
        return service.merge(req, auth.getName());
    }
}

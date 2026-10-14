package com.nforce.onehr.controller.talent;

import com.nforce.onehr.dto.talent.CreateSkillRequestRequest;
import com.nforce.onehr.dto.talent.MapSkillRequestRequest;
import com.nforce.onehr.dto.talent.SkillRequestResponse;
import com.nforce.onehr.service.talent.SkillRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/org/talent/skill-requests")
@RequiredArgsConstructor
public class SkillRequestController {

    private final SkillRequestService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SkillRequestResponse submit(@Valid @RequestBody CreateSkillRequestRequest req, Authentication auth) {
        return service.submit(req, auth.getName());
    }

    @GetMapping("/mine")
    public List<SkillRequestResponse> listMine(Authentication auth) {
        return service.listMine(auth.getName());
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('talent_skills:APPROVE:ALL')")
    public List<SkillRequestResponse> listPending() {
        return service.listPending();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('talent_skills:APPROVE:ALL')")
    public SkillRequestResponse approve(@PathVariable UUID id, Authentication auth) {
        return service.approve(id, auth.getName());
    }

    @PostMapping("/{id}/map")
    @PreAuthorize("hasAuthority('talent_skills:APPROVE:ALL')")
    public SkillRequestResponse mapToExisting(@PathVariable UUID id, @Valid @RequestBody MapSkillRequestRequest req, Authentication auth) {
        return service.mapToExisting(id, req.getExistingSkillId(), auth.getName());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('talent_skills:APPROVE:ALL')")
    public SkillRequestResponse reject(@PathVariable UUID id, Authentication auth) {
        return service.reject(id, auth.getName());
    }
}

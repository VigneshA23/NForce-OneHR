package com.nforce.onehr.controller;

import com.nforce.onehr.dto.skill.SkillDtos.RatingRequest;
import com.nforce.onehr.dto.skill.SkillDtos.RatingResponse;
import com.nforce.onehr.dto.skill.SkillDtos.SkillRequest;
import com.nforce.onehr.dto.skill.SkillDtos.SkillResponse;
import com.nforce.onehr.dto.skill.SkillDtos.TeamRatingResponse;
import com.nforce.onehr.service.SkillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-A07: managed skill taxonomy plus self and manager ratings. */
@RestController
@RequiredArgsConstructor
public class SkillController {

    private final SkillService service;

    @GetMapping("/api/skill-taxonomy")
    public List<SkillResponse> taxonomy() {
        return service.taxonomy();
    }

    @PostMapping("/api/skill-taxonomy")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public SkillResponse addSkill(@Valid @RequestBody SkillRequest req) {
        return service.addSkill(req);
    }

    @GetMapping("/api/skill-ratings/me")
    public List<RatingResponse> myRatings(Principal principal) {
        return service.myRatings(principal.getName());
    }

    @PutMapping("/api/skill-ratings/me")
    public void rateSelf(@Valid @RequestBody RatingRequest req, Principal principal) {
        service.rateSelf(principal.getName(), req);
    }

    @DeleteMapping("/api/skill-ratings/me/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSelf(@PathVariable UUID skillId, Principal principal) {
        service.removeSelf(principal.getName(), skillId);
    }

    /** The caller's direct reports' ratings, for endorsing them. */
    @GetMapping("/api/skill-ratings/team")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<TeamRatingResponse> team(Principal principal) {
        return service.teamRatings(principal.getName());
    }

    /** Manager endorse/override — only the employee's manager or HR (enforced in the service). */
    @PutMapping("/api/skill-ratings/{employeeId}/{skillId}/manager")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public void rateAsManager(@PathVariable UUID employeeId, @PathVariable UUID skillId,
                               @RequestParam int rating, Principal principal) {
        service.rateAsManager(principal.getName(), employeeId, skillId, rating);
    }
}

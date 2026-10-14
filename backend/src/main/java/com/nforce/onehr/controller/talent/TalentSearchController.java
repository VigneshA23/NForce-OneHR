package com.nforce.onehr.controller.talent;

import com.nforce.onehr.dto.talent.*;
import com.nforce.onehr.service.talent.TalentSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Talent Search — "who knows Java?" (org.talent.view), plus saved searches. */
@RestController
@RequestMapping("/api/org/talent/search")
@RequiredArgsConstructor
public class TalentSearchController {

    private final TalentSearchService service;

    @PostMapping
    @PreAuthorize("hasAuthority('talent_skills:VIEW:ALL')")
    public TalentSearchPageResponse search(@Valid @RequestBody TalentSearchRequest req) {
        return service.search(req);
    }

    @PostMapping("/saved")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('talent_skills:VIEW:ALL')")
    public SavedTalentSearchResponse saveSearch(@Valid @RequestBody CreateSavedTalentSearchRequest req, Authentication auth) {
        return service.saveSearch(req, auth.getName());
    }

    @GetMapping("/saved")
    @PreAuthorize("hasAuthority('talent_skills:VIEW:ALL')")
    public List<SavedTalentSearchResponse> listMySavedSearches(Authentication auth) {
        return service.listMySavedSearches(auth.getName());
    }

    @DeleteMapping("/saved/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('talent_skills:VIEW:ALL')")
    public void deleteSavedSearch(@PathVariable UUID id, Authentication auth) {
        service.deleteSavedSearch(id, auth.getName());
    }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.opportunity.OpportunityDtos.CloseOpportunityRequest;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.InterestResponse;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.OpportunityRequest;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.OpportunityResponse;
import com.nforce.onehr.service.OpportunityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-A10/A11/A12: internal opportunities for benched employees. Permission/visibility rules live in the service. */
@RestController
@RequestMapping("/api/opportunities")
@RequiredArgsConstructor
public class OpportunityController {

    private final OpportunityService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('opportunities:CREATE:ALL')")
    public OpportunityResponse post(@Valid @RequestBody OpportunityRequest req, Principal principal) {
        return service.post(principal.getName(), req);
    }

    @GetMapping
    public List<OpportunityResponse> list(Principal principal) {
        return service.list(principal.getName());
    }

    @PostMapping("/{id}/interest")
    @ResponseStatus(HttpStatus.CREATED)
    public void expressInterest(@PathVariable UUID id, Principal principal) {
        service.expressInterest(principal.getName(), id);
    }

    @GetMapping("/{id}/interests")
    @PreAuthorize("hasAuthority('opportunities:VIEW:ALL')")
    public List<InterestResponse> interests(@PathVariable UUID id, Principal principal) {
        return service.interests(principal.getName(), id);
    }

    /** US-A12: assign an employee — creates the allocation, ends their bench, closes the opportunity. */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('opportunities:APPROVE:ALL')")
    public OpportunityResponse close(@PathVariable UUID id, @Valid @RequestBody CloseOpportunityRequest req,
                                      Principal principal) {
        return service.close(principal.getName(), id, req);
    }
}

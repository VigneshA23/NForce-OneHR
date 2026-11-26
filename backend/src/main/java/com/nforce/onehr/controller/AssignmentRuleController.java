package com.nforce.onehr.controller;

import com.nforce.onehr.dto.assignment.AssignmentRuleDto;
import com.nforce.onehr.dto.assignment.ReevaluateResultResponse;
import com.nforce.onehr.dto.assignment.ReorderAssignmentRulesRequest;
import com.nforce.onehr.dto.assignment.UpsertAssignmentRuleRequest;
import com.nforce.onehr.service.assignment.AssignmentRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * PN-04: the shared auto-assignment rule API, one endpoint set for every policy domain
 * (PENALIZATION now; LEAVE_PLAN and WFH_POLICY reuse it). Authentication is required here; the
 * domain's own permission module (e.g. penalization:EDIT:ALL) is enforced in the service because it
 * depends on the domain.
 */
@RestController
@RequestMapping("/api/assignment-rules")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AssignmentRuleController {

    private final AssignmentRuleService service;

    @GetMapping
    public List<AssignmentRuleDto> list(@RequestParam String domain, Principal principal) {
        return service.list(principal.getName(), domain);
    }

    @PostMapping
    public AssignmentRuleDto create(@Valid @RequestBody UpsertAssignmentRuleRequest req, Principal principal) {
        return service.create(principal.getName(), req);
    }

    @PutMapping("/{id}")
    public AssignmentRuleDto update(@PathVariable UUID id, @Valid @RequestBody UpsertAssignmentRuleRequest req, Principal principal) {
        return service.update(principal.getName(), id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, Principal principal) {
        service.delete(principal.getName(), id);
    }

    @PostMapping("/reorder")
    public List<AssignmentRuleDto> reorder(@Valid @RequestBody ReorderAssignmentRulesRequest req, Principal principal) {
        return service.reorder(principal.getName(), req);
    }

    @PostMapping("/reevaluate")
    public ReevaluateResultResponse reevaluate(@RequestParam String domain, Principal principal) {
        return service.reevaluateAll(principal.getName(), domain);
    }
}

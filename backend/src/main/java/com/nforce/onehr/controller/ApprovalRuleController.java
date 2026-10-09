package com.nforce.onehr.controller;

import com.nforce.onehr.dto.workflow.*;
import com.nforce.onehr.service.ApprovalRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Workflow Studio's REST surface. SUPER_ADMIN only — Normal Employees, Managers and HR Admins
 * must not be able to view or modify approval rules (per the story's explicit authorization
 * requirement; note this is narrower than the HR_ADMIN+SUPER_ADMIN convention most other admin
 * controllers use).
 */
@RestController
@RequestMapping("/api/approval-rules")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ApprovalRuleController {

    private final ApprovalRuleService service;

    @GetMapping("/metadata")
    public ApprovalRuleMetadataResponse metadata() {
        return service.metadata();
    }

    @GetMapping
    public List<ApprovalRuleResponse> listAll() {
        return service.listAll();
    }

    @GetMapping("/{id}")
    public ApprovalRuleResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalRuleResponse create(@Valid @RequestBody ApprovalRuleRequest req, Principal principal) {
        return service.create(req, principal.getName());
    }

    @PutMapping("/{id}")
    public ApprovalRuleResponse update(@PathVariable UUID id, @Valid @RequestBody ApprovalRuleRequest req,
                                        Principal principal) {
        return service.update(id, req, principal.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/activate")
    public ApprovalRuleResponse activate(@PathVariable UUID id, Principal principal) {
        return service.activate(id, principal.getName());
    }

    @PostMapping("/{id}/deactivate")
    public ApprovalRuleResponse deactivate(@PathVariable UUID id, Principal principal) {
        return service.deactivate(id, principal.getName());
    }

    // Read-only — never persists, never activates. Deliberately not @Valid on
    // ApprovalRulePreviewRequest.approvalStages content itself (only presence) since an
    // in-progress/invalid draft is exactly what a Super Admin previews before fixing it; the
    // service's own semantic validation still runs and returns a normal 400 for a truly broken draft.
    @PostMapping("/preview")
    public ApprovalRulePreviewResponse preview(@Valid @RequestBody ApprovalRulePreviewRequest req) {
        return service.preview(req);
    }
}

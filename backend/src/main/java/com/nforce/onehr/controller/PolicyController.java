package com.nforce.onehr.controller;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.Policy;
import com.nforce.onehr.service.PolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService service;

    // ── All authenticated users ────────────────────────────

    @GetMapping("/my")
    public List<PolicyResponse> myPolicies(Principal principal) {
        return service.myPolicies(principal.getName());
    }

    @GetMapping("/my/pending-count")
    public long pendingCount(Principal principal) {
        return service.countPendingRequiredForEmployee(principal.getName());
    }

    @PostMapping("/{id}/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(Principal principal, @PathVariable Long id) {
        service.acknowledge(principal.getName(), id);
    }

    // ── HR/SA ──────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<PolicyResponse> listAll(Principal principal) {
        return service.listAll(principal.getName());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse publish(Principal principal, @Valid @ModelAttribute PublishPolicyRequest req,
                                  @RequestParam(required = false) MultipartFile attachment) throws IOException {
        return service.publish(principal.getName(), req, attachment);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse edit(Principal principal, @PathVariable Long id,
                               @Valid @RequestBody UpdatePolicyRequest req) {
        return service.editPolicy(principal.getName(), id, req);
    }

    // Attach or replace a policy's document (image/PDF/Word) independently of publishing — e.g.
    // adding one to a policy that was created without it. Same size/type rule as every other
    // attachment in the app (see AttachmentValidator).
    @PostMapping(value = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse uploadAttachment(Principal principal, @PathVariable Long id,
                                           @RequestParam MultipartFile file) throws IOException {
        return service.uploadAttachment(principal.getName(), id, file);
    }

    @PostMapping(value = "/{id}/publish-version", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse publishVersion(Principal principal, @PathVariable Long id,
                                         @Valid @ModelAttribute PublishPolicyVersionRequest req,
                                         @RequestParam(required = false) MultipartFile attachment) throws IOException {
        return service.publishNewVersion(principal.getName(), id, req, attachment);
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<PolicyResponse> versionHistory(Principal principal, @PathVariable Long id) {
        return service.getVersionHistory(principal.getName(), id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse deactivate(Principal principal, @PathVariable Long id) {
        return service.deactivatePolicy(principal.getName(), id);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public PolicyResponse reactivate(Principal principal, @PathVariable Long id) {
        return service.reactivatePolicy(principal.getName(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void delete(Principal principal, @PathVariable Long id) {
        service.deletePolicy(principal.getName(), id);
    }

    @GetMapping("/{id}/acknowledgments")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<PolicyAcknowledgmentResponse> acknowledgments(Principal principal, @PathVariable Long id) {
        return service.listAcknowledgments(principal.getName(), id);
    }

    @DeleteMapping("/{policyId}/acknowledgments/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void resetAcknowledgment(Principal principal, @PathVariable Long policyId, @PathVariable UUID userId) {
        service.resetAcknowledgment(principal.getName(), policyId, userId);
    }

    @PostMapping("/{policyId}/remind/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void remind(Principal principal, @PathVariable Long policyId, @PathVariable UUID userId) {
        service.remindEmployee(principal.getName(), policyId, userId);
    }

    @GetMapping("/pending-ack-count")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public long globalPendingAckCount() {
        return service.countAllPendingRequired();
    }

    // ── All authenticated users: policy attachment download ──
    // Any authenticated user in the policy's audience may view/download it — same visibility as
    // the policy's own description text (myPolicies), not restricted to HR/SA.

    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> getAttachment(Principal principal, @PathVariable Long id) {
        Policy p = service.getAttachment(principal.getName(), id);
        MediaType mediaType = p.getAttachmentType() != null
                ? MediaType.parseMediaType(p.getAttachmentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition cd = (mediaType == MediaType.APPLICATION_OCTET_STREAM)
                ? ContentDisposition.attachment().filename(p.getAttachmentName()).build()
                : ContentDisposition.inline().filename(p.getAttachmentName()).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaType)
                .body(p.getAttachmentData());
    }
}

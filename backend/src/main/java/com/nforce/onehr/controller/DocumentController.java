package com.nforce.onehr.controller;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.EmployeeDocument;
import com.nforce.onehr.service.DocumentService;
import com.nforce.onehr.service.PolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;
    private final PolicyService policyService;

    // ── Employee endpoints ─────────────────────────────────

    @GetMapping("/my")
    public List<EmployeeDocumentResponse> myDocuments(Principal principal) {
        return service.myDocuments(principal.getName());
    }

    @GetMapping("/my/required")
    public List<RequiredDocumentDto> myRequiredDocuments(Principal principal) {
        return service.myRequiredDocuments(principal.getName());
    }

    @GetMapping("/my/compliance")
    public ComplianceSummaryDto myCompliance(Principal principal) {
        long pendingPolicies = policyService.countPendingRequiredForEmployee(principal.getName());
        return service.getMyComplianceSummary(principal.getName(), pendingPolicies);
    }

    @PostMapping(value = "/my/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeDocumentResponse upload(
            Principal principal,
            @RequestParam Integer documentTypeId,
            @RequestParam MultipartFile file,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate
    ) throws IOException {
        return service.uploadDocument(principal.getName(), documentTypeId, file, issueDate, expiryDate);
    }

    // ── HR/SA endpoints ────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<EmployeeDocumentResponse> listAll(Principal principal) {
        return service.listAll(principal.getName());
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<EmployeeDocumentResponse> listPending(Principal principal) {
        return service.listPending(principal.getName());
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public EmployeeDocumentResponse verify(
            Principal principal,
            @PathVariable UUID id,
            @Valid @RequestBody VerifyDocumentRequest req) {
        return service.verifyDocument(principal.getName(), id, req);
    }

    @GetMapping("/kpis")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public DocumentAdminKpiDto kpis(Principal principal) {
        return service.getAdminKpis(principal.getName());
    }

    @GetMapping("/missing")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<MissingDocumentDto> listMissing(Principal principal) {
        return service.listMissing(principal.getName());
    }

    @PostMapping("/remind/{employeeUserId}/{documentTypeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public void remindMissingDocument(Principal principal,
                                      @PathVariable UUID employeeUserId,
                                      @PathVariable Integer documentTypeId) {
        service.remindMissingDocument(principal.getName(), employeeUserId, documentTypeId);
    }

    // ── File download (permission enforced in service) ─────

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> getFile(Principal principal, @PathVariable UUID id) {
        EmployeeDocument doc = service.getDocumentFile(principal.getName(), id);
        String ext = StringUtils.getFilenameExtension(doc.getFileName());
        MediaType mediaType = switch (ext == null ? "" : ext.toLowerCase()) {
            case "pdf"  -> MediaType.APPLICATION_PDF;
            case "png"  -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            default     -> MediaType.APPLICATION_OCTET_STREAM;
        };
        ContentDisposition cd = (mediaType == MediaType.APPLICATION_OCTET_STREAM)
                ? ContentDisposition.attachment().filename(doc.getFileName()).build()
                : ContentDisposition.inline().filename(doc.getFileName()).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(mediaType)
                .body(doc.getFileData());
    }
}

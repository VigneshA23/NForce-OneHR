package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.DocumentType;
import com.nforce.onehr.entity.Employee;
import java.util.ArrayList;
import com.nforce.onehr.entity.EmployeeDocument;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final EmployeeDocumentRepository docRepo;
    private final DocumentTypeRepository docTypeRepo;
    private final EmployeeRepository employeeRepo;
    private final UserRepository userRepo;
    private final NotificationRepository notificationRepo;
    private final NotificationService notificationService;

    // ── Employee: list my documents with required doc computation ──

    @Transactional(readOnly = true)
    public List<EmployeeDocumentResponse> myDocuments(String actorEmail) {
        UUID actorId = requireUser(actorEmail).getId();
        checkExpiryReminders(actorId);
        return docRepo.findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(actorId).stream()
                .map(EmployeeDocumentResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RequiredDocumentDto> myRequiredDocuments(String actorEmail) {
        UUID actorId = requireUser(actorEmail).getId();
        return requiredDocumentsFor(actorId);
    }

    /**
     * Same computation as myRequiredDocuments, keyed by an arbitrary employee
     * rather than the caller — for other services (e.g. Onboarding) to reuse
     * without duplicating the required-document logic.
     */
    @Transactional(readOnly = true)
    public List<RequiredDocumentDto> requiredDocumentsFor(UUID employeeUserId) {
        Employee emp = employeeRepo.findById(employeeUserId).orElse(null);

        List<DocumentType> allActive = docTypeRepo.findByActiveTrueOrderByNameAsc();
        List<DocumentType> applicable = allActive.stream()
                .filter(dt -> isApplicable(dt, emp))
                .collect(Collectors.toList());

        Map<Integer, EmployeeDocument> uploaded = docRepo
                .findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(employeeUserId).stream()
                .collect(Collectors.toMap(d -> d.getDocumentType().getId(), d -> d, (a, b) -> a));

        return applicable.stream().map(dt -> {
            EmployeeDocument doc = uploaded.get(dt.getId());
            boolean expiringSoon = doc != null && doc.getExpiryDate() != null
                    && doc.getExpiryDate().isAfter(LocalDate.now())
                    && ChronoUnit.DAYS.between(LocalDate.now(), doc.getExpiryDate()) <= 30;
            return new RequiredDocumentDto(
                    dt.getId(), dt.getName(), dt.isRequiresVerification(), dt.isRequiresExpiryDate(),
                    doc != null, doc != null ? doc.getStatus() : null, expiringSoon);
        }).collect(Collectors.toList());
    }

    // ── Employee: upload or re-upload ──

    @Transactional
    public EmployeeDocumentResponse uploadDocument(String actorEmail, Integer documentTypeId,
                                                   MultipartFile file, LocalDate issueDate, LocalDate expiryDate) throws IOException {
        if (issueDate != null && expiryDate != null && expiryDate.isBefore(issueDate)) {
            throw new IllegalArgumentException("Expiry date cannot be earlier than issue date");
        }
        UUID actorId = requireUser(actorEmail).getId();
        DocumentType dt = docTypeRepo.findById(documentTypeId)
                .orElseThrow(() -> new NoSuchElementException("Document type not found: " + documentTypeId));
        // A deactivated type no longer accepts new submissions; documents already uploaded under
        // it are left exactly as they are.
        if (!dt.isActive()) {
            throw new IllegalStateException("\"" + dt.getName() + "\" is no longer accepting uploads");
        }
        // Same employment-type/location rule as the required-documents list.
        if (!isApplicable(dt, employeeRepo.findById(actorId).orElse(null))) {
            throw new IllegalArgumentException("\"" + dt.getName() + "\" does not apply to your employment type or location");
        }
        if (dt.isRequiresExpiryDate() && expiryDate == null) {
            throw new IllegalArgumentException("Expiry date is required for " + dt.getName());
        }

        // Retain document history on re-upload (AC1): the current version, if any, is never
        // mutated — it's flagged superseded and a brand-new row becomes current. Flushing the
        // supersede update before inserting the new row matters: Hibernate flushes inserts before
        // updates within one flush, and both rows would otherwise briefly be non-superseded at
        // once, tripping the ux_employee_documents_current partial unique index (V190).
        Optional<EmployeeDocument> currentOpt = docRepo.findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(actorId, documentTypeId);
        // A document awaiting HR review can't be silently re-uploaded over — that orphans the
        // pending row (HR can never act on it once superseded) and leaves stale "Pending Review"
        // entries in Document History forever. The employee must withdraw() it first, which frees
        // the slot for a fresh upload. Verified/Rejected/absent documents are unaffected.
        if (currentOpt.isPresent() && "PENDING_VERIFICATION".equals(currentOpt.get().getStatus())) {
            throw new IllegalStateException("This document is pending review. Withdraw it before uploading a new version.");
        }
        int nextVersion = 1;
        UUID previousVersionId = null;
        if (currentOpt.isPresent()) {
            EmployeeDocument current = currentOpt.get();
            current.setSuperseded(true);
            docRepo.saveAndFlush(current);
            nextVersion = current.getVersionNumber() + 1;
            previousVersionId = current.getId();
        }

        EmployeeDocument doc = EmployeeDocument.builder()
                .employeeUserId(actorId)
                .documentType(dt)
                .fileName(file.getOriginalFilename())
                .fileUrl("")
                .fileData(file.getBytes())
                .issueDate(issueDate)
                .expiryDate(expiryDate)
                .status(dt.isRequiresVerification() ? "PENDING_VERIFICATION" : "VERIFIED")
                .versionNumber(nextVersion)
                .previousVersionId(previousVersionId)
                .superseded(false)
                .build();
        doc = docRepo.save(doc);
        doc.setFileUrl("/api/documents/" + doc.getId() + "/file");
        doc = docRepo.save(doc);
        return EmployeeDocumentResponse.from(doc);
    }

    // ── Employee: withdraw a document still awaiting review ──

    /**
     * Frees up the (employee, documentType) slot without HR ever having to act on it: the
     * current row is marked WITHDRAWN and superseded, exactly like a re-upload's supersede step,
     * but without inserting a replacement row. With no current row left, the document type
     * reverts to "not submitted" everywhere (required-document computation, onboarding
     * breakdown, KPIs) for free — none of those queries need to know about WITHDRAWN.
     */
    @Transactional
    public EmployeeDocumentResponse withdrawDocument(String actorEmail, UUID documentId) {
        UUID actorId = requireUser(actorEmail).getId();
        EmployeeDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        if (!doc.getEmployeeUserId().equals(actorId)) {
            throw new AccessDeniedException("Access denied");
        }
        if (doc.isSuperseded() || !"PENDING_VERIFICATION".equals(doc.getStatus())) {
            throw new IllegalStateException("Only a document pending review can be withdrawn.");
        }
        doc.setStatus("WITHDRAWN");
        doc.setSuperseded(true);
        return EmployeeDocumentResponse.from(docRepo.save(doc));
    }

    // ── HR/SA & owning employee: full version history for a document ──

    @Transactional(readOnly = true)
    public List<EmployeeDocumentResponse> getDocumentHistory(String actorEmail, UUID documentId) {
        User actor = requireUser(actorEmail);
        EmployeeDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        boolean isAdmin = actor.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
        boolean isOwner = doc.getEmployeeUserId().equals(actor.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException("Access denied");
        }
        return docRepo.findByEmployeeUserIdAndDocumentTypeIdOrderByVersionNumberDesc(
                        doc.getEmployeeUserId(), doc.getDocumentType().getId()).stream()
                .map(EmployeeDocumentResponse::from)
                .collect(Collectors.toList());
    }

    // ── HR/SA: list pending documents (excludes the CALLER's own — see adminVisibleDocuments) ──

    @Transactional(readOnly = true)
    public List<EmployeeDocumentResponse> listPending(String actorEmail) {
        UUID actorId = requireUser(actorEmail).getId();
        requireAdminRole(actorEmail);
        List<EmployeeDocument> docs = docRepo.findByStatusOrderByUploadedAtDesc("PENDING_VERIFICATION")
                .stream().filter(d -> !d.getEmployeeUserId().equals(actorId)).collect(Collectors.toList());
        Map<UUID, String> names = nameMapFor(docs.stream().map(EmployeeDocument::getEmployeeUserId).collect(Collectors.toSet()));
        return docs.stream().map(d -> EmployeeDocumentResponse.from(d, names.get(d.getEmployeeUserId()))).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeDocumentResponse> listAll(String actorEmail) {
        UUID actorId = requireUser(actorEmail).getId();
        requireAdminRole(actorEmail);
        List<EmployeeDocument> docs = adminVisibleDocuments(actorId);
        Map<UUID, String> names = nameMapFor(docs.stream().map(EmployeeDocument::getEmployeeUserId).collect(Collectors.toSet()));
        return docs.stream().map(d -> EmployeeDocumentResponse.from(d, names.get(d.getEmployeeUserId()))).collect(Collectors.toList());
    }

    /**
     * HR/SA: current (non-superseded) documents for one specific employee — e.g. the
     * "View Documents" action from within Onboarding. Scoped strictly to employeeUserId
     * so one employee's documents are never visible while viewing another's.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDocumentResponse> documentsForEmployee(String actorEmail, UUID employeeUserId) {
        requireAdminRole(actorEmail);
        Employee emp = employeeRepo.findById(employeeUserId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + employeeUserId));
        return docRepo.findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(employeeUserId).stream()
                .map(d -> EmployeeDocumentResponse.from(d, emp.getFullName()))
                .collect(Collectors.toList());
    }

    // ── HR/SA: verify or reject ──

    @Transactional
    public EmployeeDocumentResponse verifyDocument(String actorEmail, UUID documentId, VerifyDocumentRequest req) {
        User actor = requireUser(actorEmail);
        requireAdminRole(actorEmail);

        EmployeeDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));

        if (doc.getEmployeeUserId().equals(actor.getId())) {
            throw new AccessDeniedException("Cannot verify your own document");
        }
        // Only the current, still-pending version can be actioned — guards against a stale screen
        // (another admin already decided, or the employee re-uploaded in the meantime).
        if (doc.isSuperseded()) {
            throw new IllegalStateException("This document has been replaced by a newer upload. Please refresh.");
        }
        if (!"PENDING_VERIFICATION".equals(doc.getStatus())) {
            throw new IllegalStateException("This document has already been reviewed. Please refresh.");
        }
        if ("VERIFY".equalsIgnoreCase(req.getAction())) {
            doc.setStatus("VERIFIED");
            doc.setVerifiedBy(actor.getId());
            doc.setVerifiedAt(Instant.now());
            doc.setRejectionReason(null);
            notificationService.send(doc.getEmployeeUserId(), "DOCUMENT_VERIFIED",
                    "Document Verified",
                    "Your " + doc.getDocumentType().getName() + " has been verified.",
                    "/documents");
        } else if ("REJECT".equalsIgnoreCase(req.getAction())) {
            String reason = req.getRejectionReason() == null ? "" : req.getRejectionReason().trim();
            if (reason.isEmpty()) {
                throw new IllegalArgumentException("Rejection reason is required");
            }
            doc.setStatus("REJECTED");
            doc.setVerifiedBy(actor.getId());
            doc.setVerifiedAt(Instant.now());
            doc.setRejectionReason(reason);
            notificationService.send(doc.getEmployeeUserId(), "DOCUMENT_REJECTED",
                    "Document Rejected",
                    "Your " + doc.getDocumentType().getName() + " was rejected: " + reason,
                    "/documents");
        } else {
            throw new IllegalArgumentException("Invalid action: " + req.getAction());
        }
        return EmployeeDocumentResponse.from(docRepo.save(doc));
    }

    // ── Serve file (HR/SA only; employee may get own) ──

    @Transactional(readOnly = true)
    public EmployeeDocument getDocumentFile(String actorEmail, UUID documentId) {
        User actor = requireUser(actorEmail);
        EmployeeDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        boolean isAdmin = actor.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
        boolean isOwner = doc.getEmployeeUserId().equals(actor.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException("Access denied");
        }
        return doc;
    }

    // ── HR/SA: list missing documents (excludes HR/SA employees) ──

    @Transactional(readOnly = true)
    public List<MissingDocumentDto> listMissing(String actorEmail) {
        requireAdminRole(actorEmail);
        List<Employee> employees = employeeRepo.findAllActiveNonAdminWithDetails();
        List<DocumentType> docTypes = docTypeRepo.findByActiveTrueOrderByNameAsc();

        Set<String> uploaded = docRepo.findAllEmployeeDocTypePairs().stream()
                .map(r -> r[0].toString() + ":" + r[1].toString())
                .collect(Collectors.toSet());

        List<MissingDocumentDto> missing = new ArrayList<>();
        for (Employee emp : employees) {
            for (DocumentType dt : docTypes) {
                if (isApplicable(dt, emp) && !uploaded.contains(emp.getUserId().toString() + ":" + dt.getId().toString())) {
                    missing.add(new MissingDocumentDto(emp.getUserId(), emp.getFullName(), dt.getId(), dt.getName()));
                }
            }
        }
        return missing;
    }

    // ── HR/SA: remind employee about missing document ──

    @Transactional
    public void remindMissingDocument(String actorEmail, UUID employeeUserId, Integer documentTypeId) {
        requireAdminRole(actorEmail);
        DocumentType dt = docTypeRepo.findById(documentTypeId)
                .orElseThrow(() -> new NoSuchElementException("Document type not found: " + documentTypeId));
        // Same eligibility as listMissing: only an active, non-admin employee who genuinely hasn't
        // submitted an active, applicable document type can be reminded about it.
        Employee emp = employeeRepo.findById(employeeUserId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + employeeUserId));
        User empUser = emp.getUser();
        if (empUser == null || empUser.getDeletedAt() != null || !empUser.isActive()
                || userRepo.findAdminUserIds().contains(employeeUserId)) {
            throw new IllegalArgumentException("Reminders can only be sent to active employees");
        }
        if (!dt.isActive() || !isApplicable(dt, emp)) {
            throw new IllegalArgumentException("\"" + dt.getName() + "\" is not required for this employee");
        }
        if (docRepo.findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(employeeUserId, documentTypeId).isPresent()) {
            throw new IllegalStateException("This employee has already submitted their " + dt.getName());
        }
        notificationService.send(employeeUserId, "DOCUMENT_REMINDER",
                "Document Required: " + dt.getName(),
                "Please upload your " + dt.getName() + " document to complete your compliance profile.",
                "/documents");
    }

    // ── HR KPIs ──

    @Transactional(readOnly = true)
    public DocumentAdminKpiDto getAdminKpis(String actorEmail) {
        UUID actorId = requireUser(actorEmail).getId();
        requireAdminRole(actorEmail);
        // Computed from exactly the rows the admin lists show (current version, non-deleted,
        // excluding the caller's own documents) so every KPI card matches its tab. Previously
        // totalDocuments was docRepo.count() — every row including superseded versions, deleted
        // employees and the caller's own documents.
        List<EmployeeDocument> docs = adminVisibleDocuments(actorId);
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(30);
        List<EmployeeDocument> pending = docs.stream()
                .filter(d -> "PENDING_VERIFICATION".equals(d.getStatus())).collect(Collectors.toList());
        long expiring = docs.stream()
                .filter(d -> "VERIFIED".equals(d.getStatus()) && d.getExpiryDate() != null
                        && !d.getExpiryDate().isBefore(today) && !d.getExpiryDate().isAfter(horizon))
                .count();
        return new DocumentAdminKpiDto(
                pending.size(),
                pending.stream().map(EmployeeDocument::getEmployeeUserId).distinct().count(),
                expiring,
                docs.size());
    }

    // ── Employee compliance summary ──

    @Transactional(readOnly = true)
    public ComplianceSummaryDto getMyComplianceSummary(String actorEmail, long pendingPolicies) {
        UUID actorId = requireUser(actorEmail).getId();
        Employee emp = employeeRepo.findById(actorId).orElse(null);

        List<DocumentType> applicable = docTypeRepo.findByActiveTrueOrderByNameAsc().stream()
                .filter(dt -> isApplicable(dt, emp))
                .collect(Collectors.toList());

        Map<Integer, EmployeeDocument> uploaded = docRepo
                .findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(actorId).stream()
                .collect(Collectors.toMap(d -> d.getDocumentType().getId(), d -> d, (a, b) -> a));

        int totalRequired = applicable.size();
        int uploadedCount = 0, verified = 0, pending = 0, rejected = 0, missing = 0, expiringSoon = 0;

        for (DocumentType dt : applicable) {
            EmployeeDocument doc = uploaded.get(dt.getId());
            if (doc == null) {
                missing++;
            } else {
                uploadedCount++;
                switch (doc.getStatus()) {
                    case "VERIFIED" -> verified++;
                    case "PENDING_VERIFICATION" -> pending++;
                    case "REJECTED" -> rejected++;
                }
                if (doc.getExpiryDate() != null && doc.getExpiryDate().isAfter(LocalDate.now())
                        && ChronoUnit.DAYS.between(LocalDate.now(), doc.getExpiryDate()) <= 30) {
                    expiringSoon++;
                }
            }
        }
        return new ComplianceSummaryDto(totalRequired, uploadedCount, verified, pending, rejected, missing, expiringSoon, (int) pendingPolicies);
    }

    // ── Helpers ──

    // The row set behind the admin "All" list and the KPIs: current versions of non-deleted
    // employees, excluding the CALLING admin's own documents.
    //
    // Bug fix (ONEHR: "HR Admin: unable to verify or reject uploaded employee documents"): this
    // used to exclude every document belonging to ANY HR_ADMIN/SUPER_ADMIN-role employee, from
    // EVERY admin's view — not just from that document owner's own view. A document uploaded by
    // one HR Admin (e.g. before they were promoted from Employee, or an HR Admin who is also a
    // new joiner) was therefore invisible in every admin list and permanently stuck in
    // PENDING_VERIFICATION: verifyDocument() itself only blocks the OWNER from actioning their own
    // document, but there was no way for a DIFFERENT admin to ever discover its id to act on it,
    // since it never appeared in listPending/listAll/getAdminKpis for anyone. Scoping the
    // exclusion to the CALLER's own id (mirrors verifyDocument's own self-review block) fixes
    // that: any other admin can still see and act on it, while a caller still never sees — or is
    // invited to self-review — their own upload in this admin queue.
    private List<EmployeeDocument> adminVisibleDocuments(UUID actorId) {
        return docRepo.findAllWithActiveEmployee().stream()
                .filter(d -> !d.getEmployeeUserId().equals(actorId))
                .collect(Collectors.toList());
    }

    private boolean isApplicable(DocumentType dt, Employee emp) {
        if (emp == null) return true;
        if (dt.getApplicableEmploymentTypes() != null && !dt.getApplicableEmploymentTypes().isBlank()) {
            List<String> types = Arrays.asList(dt.getApplicableEmploymentTypes().split(","));
            if (types.stream().noneMatch(t -> t.trim().equalsIgnoreCase(emp.getEmploymentType()))) return false;
        }
        if (dt.getApplicableLocations() != null && !dt.getApplicableLocations().isBlank()) {
            String locName = emp.getLocation() != null ? emp.getLocation().getName() : null;
            if (locName == null) return false;
            List<String> locs = Arrays.asList(dt.getApplicableLocations().split(","));
            if (locs.stream().noneMatch(l -> l.trim().equalsIgnoreCase(locName))) return false;
        }
        return true;
    }

    private void checkExpiryReminders(UUID userId) {
        docRepo.findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(userId).forEach(doc -> {
            if (doc.getExpiryDate() == null || !"VERIFIED".equals(doc.getStatus())) return;
            long days = ChronoUnit.DAYS.between(LocalDate.now(), doc.getExpiryDate());
            if (days < 0) return;
            for (int threshold : new int[]{60, 30, 7}) {
                if (days <= threshold) {
                    String linkPath = "/documents?expiry=" + doc.getId();
                    if (!notificationRepo.existsByUserIdAndLinkPath(userId, linkPath)) {
                        notificationService.send(userId, "DOCUMENT_EXPIRY",
                                "Document Expiring Soon",
                                doc.getDocumentType().getName() + " expires in " + days + " day(s). Please renew.",
                                linkPath);
                    }
                    break;
                }
            }
        });
    }

    private Map<UUID, String> nameMapFor(Set<UUID> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        return employeeRepo.findNamesByUserIds(ids).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1]));
    }

    private User requireUser(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + email));
    }

    private void requireAdminRole(String email) {
        User u = requireUser(email);
        boolean isAdmin = u.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
        if (!isAdmin) {
            throw new AccessDeniedException("Access denied");
        }
    }
}

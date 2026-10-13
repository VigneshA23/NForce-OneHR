package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, UUID> {

    // All versions (current + superseded) for an employee — used only where history, not just
    // the current document, is wanted.
    List<EmployeeDocument> findByEmployeeUserIdOrderByUploadedAtDesc(UUID employeeUserId);

    // Current-only, for the "one document per type" views (my documents, required-document
    // computation, compliance summary, expiry reminders) — AC5.
    List<EmployeeDocument> findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(UUID employeeUserId);

    Optional<EmployeeDocument> findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(UUID employeeUserId, Integer documentTypeId);

    // Full version history for one employee/document-type, newest first — backs the document
    // history endpoint (AC2).
    List<EmployeeDocument> findByEmployeeUserIdAndDocumentTypeIdOrderByVersionNumberDesc(UUID employeeUserId, Integer documentTypeId);

    // Backs DocumentService#listPending — the HR-wide pending-verification queue. Joins User to
    // exclude soft-deleted employees' document rows. Current-version-only so a superseded
    // rejected/pending row never reappears in the queue after re-upload.
    @Query("SELECT d FROM EmployeeDocument d JOIN User u ON u.id = d.employeeUserId "
         + "WHERE d.status = :status AND d.superseded = false AND u.deletedAt IS NULL ORDER BY d.uploadedAt DESC")
    List<EmployeeDocument> findByStatusOrderByUploadedAtDesc(@Param("status") String status);

    // Backs DocumentService#listAll — the HR-wide "every document" view, which previously used
    // the base findAll(). A dedicated method rather than overriding findAll() itself, matching
    // the EmployeeRepository.findAllWithDetails() precedent for "the same rows as findAll(), but
    // deletedAt-aware." Current-version-only (AC5) — history is exposed only via the dedicated
    // history endpoint.
    @Query("SELECT d FROM EmployeeDocument d JOIN User u ON u.id = d.employeeUserId WHERE u.deletedAt IS NULL AND d.superseded = false")
    List<EmployeeDocument> findAllWithActiveEmployee();

    @Query("SELECT d.employeeUserId, d.documentType.id FROM EmployeeDocument d WHERE d.superseded = false")
    List<Object[]> findAllEmployeeDocTypePairs();

    List<EmployeeDocument> findByEmployeeUserIdAndStatus(UUID employeeUserId, String status);
}

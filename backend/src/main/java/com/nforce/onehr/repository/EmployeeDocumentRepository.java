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

    List<EmployeeDocument> findByEmployeeUserIdOrderByUploadedAtDesc(UUID employeeUserId);

    Optional<EmployeeDocument> findByEmployeeUserIdAndDocumentTypeId(UUID employeeUserId, Integer documentTypeId);

    // Backs DocumentService#listPending — the HR-wide pending-verification queue. Joins User to
    // exclude soft-deleted employees' document rows.
    @Query("SELECT d FROM EmployeeDocument d JOIN User u ON u.id = d.employeeUserId "
         + "WHERE d.status = :status AND u.deletedAt IS NULL ORDER BY d.uploadedAt DESC")
    List<EmployeeDocument> findByStatusOrderByUploadedAtDesc(@Param("status") String status);

    // Backs DocumentService#listAll — the HR-wide "every document" view, which previously used
    // the base findAll(). A dedicated method rather than overriding findAll() itself, matching
    // the EmployeeRepository.findAllWithDetails() precedent for "the same rows as findAll(), but
    // deletedAt-aware."
    @Query("SELECT d FROM EmployeeDocument d JOIN User u ON u.id = d.employeeUserId WHERE u.deletedAt IS NULL")
    List<EmployeeDocument> findAllWithActiveEmployee();

    @Query("SELECT COUNT(DISTINCT d.employeeUserId) FROM EmployeeDocument d WHERE d.status = 'PENDING_VERIFICATION'")
    long countEmployeesWithPendingDocs();

    @Query("SELECT COUNT(d) FROM EmployeeDocument d WHERE d.status = 'PENDING_VERIFICATION'")
    long countPendingDocuments();

    @Query(value = "SELECT COUNT(*) FROM employee_documents WHERE expiry_date IS NOT NULL AND expiry_date <= CURRENT_DATE + INTERVAL '30 days' AND status = 'VERIFIED'", nativeQuery = true)
    long countExpiringWithin30Days();

    @Query("SELECT d.employeeUserId, d.documentType.id FROM EmployeeDocument d")
    List<Object[]> findAllEmployeeDocTypePairs();

    List<EmployeeDocument> findByEmployeeUserIdAndStatus(UUID employeeUserId, String status);
}

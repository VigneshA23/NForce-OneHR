package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, UUID> {

    List<EmployeeDocument> findByEmployeeUserIdOrderByUploadedAtDesc(UUID employeeUserId);

    Optional<EmployeeDocument> findByEmployeeUserIdAndDocumentTypeId(UUID employeeUserId, Integer documentTypeId);

    List<EmployeeDocument> findByStatusOrderByUploadedAtDesc(String status);

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

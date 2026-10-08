package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeCertificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeCertificateRepository extends JpaRepository<EmployeeCertificate, UUID> {

    List<EmployeeCertificate> findByEmployeeUserIdOrderByCreatedAtAsc(UUID employeeUserId);

    // Backs the Team Performance roster's inline certificate badges — one query for the whole
    // team instead of one per direct report.
    List<EmployeeCertificate> findByEmployeeUserIdInOrderByCreatedAtAsc(Collection<UUID> employeeUserIds);
}

package com.nforce.onehr.repository;

import com.nforce.onehr.entity.CertificateCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CertificateCategoryRepository extends JpaRepository<CertificateCategory, UUID> {

    List<CertificateCategory> findByActiveTrueOrderByNameAsc();

    List<CertificateCategory> findAllByOrderByNameAsc();
}

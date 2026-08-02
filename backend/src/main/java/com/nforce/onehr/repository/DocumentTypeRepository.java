package com.nforce.onehr.repository;

import com.nforce.onehr.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentTypeRepository extends JpaRepository<DocumentType, Integer> {

    List<DocumentType> findByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    @Query("SELECT COUNT(d) FROM EmployeeDocument d WHERE d.documentType.id = :typeId")
    long countUsageByTypeId(Integer typeId);
}

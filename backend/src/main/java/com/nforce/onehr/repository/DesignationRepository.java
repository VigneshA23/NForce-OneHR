package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Designation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DesignationRepository extends JpaRepository<Designation, UUID> {
    boolean existsByTitleIgnoreCase(String title);
}

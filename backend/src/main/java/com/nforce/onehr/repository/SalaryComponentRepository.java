package com.nforce.onehr.repository;

import com.nforce.onehr.entity.SalaryComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** US-C20 — the shared Salary Component catalog. */
@Repository
public interface SalaryComponentRepository extends JpaRepository<SalaryComponent, UUID> {

    Optional<SalaryComponent> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<SalaryComponent> findAllByOrderByNameAsc();
}

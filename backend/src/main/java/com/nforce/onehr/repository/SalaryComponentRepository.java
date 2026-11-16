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

    // Display-order is the catalog's real sort key once set (see SalaryComponent#displayOrder's
    // own doc comment); name only breaks a tie between rows that share an order value (freshly
    // created ones all default to 0 until explicitly reordered).
    List<SalaryComponent> findAllByOrderByDisplayOrderAscNameAsc();
}

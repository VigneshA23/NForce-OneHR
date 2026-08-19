package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpdeskCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HelpdeskCategoryRepository extends JpaRepository<HelpdeskCategory, Integer> {

    List<HelpdeskCategory> findByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}

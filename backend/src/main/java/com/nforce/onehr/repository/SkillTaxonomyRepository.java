package com.nforce.onehr.repository;

import com.nforce.onehr.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public interface SkillTaxonomyRepository extends JpaRepository<SkillTaxonomy, UUID> {
    boolean existsBySkillIgnoreCase(String skill);
}

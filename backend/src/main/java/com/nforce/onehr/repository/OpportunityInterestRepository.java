package com.nforce.onehr.repository;

import com.nforce.onehr.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public interface OpportunityInterestRepository extends JpaRepository<OpportunityInterest, UUID> {
    boolean existsByOpportunityIdAndEmployeeUserId(UUID opportunityId, UUID employeeUserId);

    List<OpportunityInterest> findByOpportunityIdOrderByExpressedAtAsc(UUID opportunityId);

    List<OpportunityInterest> findByEmployeeUserId(UUID employeeUserId);
}

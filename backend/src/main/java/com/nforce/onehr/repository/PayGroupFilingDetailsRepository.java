package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayGroupFilingDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// payGroupId IS the primary key (see the entity's own doc comment), so plain findById/existsById
// already is "find by pay group" — no custom query needed.
@Repository
public interface PayGroupFilingDetailsRepository extends JpaRepository<PayGroupFilingDetails, UUID> {
}

package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PtRegisteredLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PtRegisteredLocationRepository extends JpaRepository<PtRegisteredLocation, UUID> {

    List<PtRegisteredLocation> findByPayGroupIdOrderByStateNameAscLocationNameAsc(UUID payGroupId);
}

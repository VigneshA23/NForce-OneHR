package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveYearEndRunLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LeaveYearEndRunLineRepository extends JpaRepository<LeaveYearEndRunLine, UUID> {

    List<LeaveYearEndRunLine> findByRunId(UUID runId);
}

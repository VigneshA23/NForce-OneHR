package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveYearEndRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveYearEndRunRepository extends JpaRepository<LeaveYearEndRun, UUID> {

    List<LeaveYearEndRun> findByFromYearOrderByCreatedAtDesc(Integer fromYear);

    Optional<LeaveYearEndRun> findByFromYearAndStatus(Integer fromYear, String status);
}

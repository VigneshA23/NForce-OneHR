package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayrollRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

    Optional<PayrollRun> findByPayGroupIdAndCycleStartAndCycleEnd(UUID payGroupId, LocalDate cycleStart, LocalDate cycleEnd);

    List<PayrollRun> findByPayGroupIdOrderByCycleStartDesc(UUID payGroupId);

    // US-C27's "rollback only allowed while the next cycle is not locked" guard — any run for the
    // same pay group with a later cycleStart that's already APPROVED/DISBURSED blocks rollback.
    @Query("SELECT r FROM PayrollRun r WHERE r.payGroupId = :payGroupId AND r.cycleStart > :cycleStart "
         + "AND r.status IN ('APPROVED', 'DISBURSED')")
    List<PayrollRun> findLaterLockedRuns(@Param("payGroupId") UUID payGroupId, @Param("cycleStart") LocalDate cycleStart);
}

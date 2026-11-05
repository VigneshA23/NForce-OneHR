package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeCompensation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeCompensationRepository extends JpaRepository<EmployeeCompensation, UUID> {

    // JOIN FETCH the structure — EmployeeCompensationService#toResponse always reads
    // structure.getName(), same N+1 rationale as SalaryStructureComponentRepository's own queries.
    @Query("SELECT c FROM EmployeeCompensation c JOIN FETCH c.structure WHERE c.employeeId = :employeeId AND c.effectiveTo IS NULL")
    Optional<EmployeeCompensation> findByEmployeeIdAndEffectiveToIsNull(@Param("employeeId") UUID employeeId);

    @Query("SELECT c FROM EmployeeCompensation c JOIN FETCH c.structure WHERE c.employeeId = :employeeId ORDER BY c.effectiveFrom DESC")
    List<EmployeeCompensation> findByEmployeeIdOrderByEffectiveFromDesc(@Param("employeeId") UUID employeeId);

    @Modifying
    @Query("UPDATE EmployeeCompensation c SET c.effectiveTo = :effectiveTo WHERE c.employeeId = :employeeId AND c.effectiveTo IS NULL")
    void closeCurrentEntry(@Param("employeeId") UUID employeeId, @Param("effectiveTo") LocalDate effectiveTo);

    // US-C08 — every employee's compensation row effective on a given date (point-in-time, not
    // "whatever's current now"), used to discover who's included in a run and with what
    // structure/CTC, correct even if generation happens well after the cycle closed.
    @Query("SELECT c FROM EmployeeCompensation c JOIN FETCH c.structure "
         + "WHERE c.effectiveFrom <= :date AND (c.effectiveTo IS NULL OR c.effectiveTo > :date)")
    List<EmployeeCompensation> findAllEffectiveAt(@Param("date") LocalDate date);
}

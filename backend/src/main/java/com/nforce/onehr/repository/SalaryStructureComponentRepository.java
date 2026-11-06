package com.nforce.onehr.repository;

import com.nforce.onehr.entity.SalaryStructureComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SalaryStructureComponentRepository extends JpaRepository<SalaryStructureComponent, UUID> {

    // JOIN FETCH the catalog entry — SalaryStructureService#toComponentResponse always reads
    // component.getName()/getType()/etc., so this avoids one lazy load per row (same N+1
    // rationale as LeaveRequestRepository's own JOIN FETCH queries).
    @Query("SELECT sc FROM SalaryStructureComponent sc JOIN FETCH sc.component WHERE sc.structure.id = :structureId")
    List<SalaryStructureComponent> findByStructureId(@Param("structureId") UUID structureId);

    // Backs SalaryStructureService#list — one query for every structure's components instead of
    // one per structure.
    @Query("SELECT sc FROM SalaryStructureComponent sc JOIN FETCH sc.component WHERE sc.structure.id IN :structureIds")
    List<SalaryStructureComponent> findByStructureIdIn(@Param("structureIds") List<UUID> structureIds);

    // Backs SalaryComponentService's "can't deactivate/delete a catalog entry that's in use"
    // guard (US-C20's own rule).
    boolean existsByComponentId(UUID componentId);

    // Backs EmployeeCompensationService's US-C02 guard: a structure with zero components can't
    // be assigned to an employee — there'd be nothing to compute a payslip from.
    boolean existsByStructureId(UUID structureId);
}

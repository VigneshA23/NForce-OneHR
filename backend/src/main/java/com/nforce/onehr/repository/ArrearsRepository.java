package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Arrears;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ArrearsRepository extends JpaRepository<Arrears, UUID> {

    List<Arrears> findByEmployeeIdAndStatus(UUID employeeId, String status);

    List<Arrears> findByEmployeeIdIn(List<UUID> employeeIds);
}

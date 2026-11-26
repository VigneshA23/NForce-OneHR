package com.nforce.onehr.repository;

import com.nforce.onehr.entity.CompOffEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompOffEventRepository extends JpaRepository<CompOffEvent, UUID> {
    List<CompOffEvent> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);
}

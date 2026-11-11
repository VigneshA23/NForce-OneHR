package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /** Newest first — used to read a role's change history (role admin events are low-volume). */
    List<AuditLog> findByActionInOrderByOccurredAtDesc(Collection<String> actions, Pageable pageable);
}

package com.nforce.onehr.service;

import com.nforce.onehr.entity.AuditLog;
import com.nforce.onehr.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void log(UUID actorId, String action, UUID targetId) {
        log(actorId, action, targetId, null, null);
    }

    // REQUIRES_NEW + noRollbackFor: audit must never roll back the caller's transaction.
    // noRollbackFor prevents Spring from marking the inner TX rollback-only on any exception.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = Exception.class)
    public void log(UUID actorId, String action, UUID targetId, String beforeState, String afterState) {
        try {
            AuditLog entry = AuditLog.builder()
                    .actorId(actorId)
                    .action(action)
                    .targetId(targetId)
                    .beforeState(beforeState)
                    .afterState(afterState)
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write audit log: action={} actor={}", action, actorId, e);
        }
    }
}

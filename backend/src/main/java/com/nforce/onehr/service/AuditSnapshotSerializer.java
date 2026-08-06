package com.nforce.onehr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Serializes small before/after field snapshots for {@link AuditService}'s 5-arg log()
 * overload. Deliberately takes a caller-built {@code Map} of only the fields worth diffing —
 * never a whole entity — so sensitive fields (password hashes, profile photo bytes, etc.) are
 * never at risk of being captured just because a field gets added to an entity later.
 *
 * <p><b>Security rule for callers:</b> never put a password hash or other credential material
 * into the map passed here. Self-service password actions in AuthService (login and password
 * change/reset events) deliberately don't call this at all — only
 * UserManagementService.resetPassword captures a safe, hash-free flag flip
 * ({@code mustChangePassword}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditSnapshotSerializer {

    private final ObjectMapper objectMapper;

    /** Never throws — a serialization failure degrades to a null (missing) snapshot, not a broken transaction. */
    public String toJson(Map<String, Object> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit snapshot: {}", e.getMessage());
            return null;
        }
    }
}

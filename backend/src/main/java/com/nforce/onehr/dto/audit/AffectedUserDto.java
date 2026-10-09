package com.nforce.onehr.dto.audit;

import lombok.*;

import java.util.UUID;

/**
 * One entry in {@link AuditLogEntryDto#getAffectedUsers()} — a named, navigable reference to an
 * employee affected by an audit event whose "affected user" isn't a single person but a set of
 * them (e.g. every employee governed by a penalization policy whose rules just changed). The
 * frontend renders these as a clickable list linking to each employee's directory profile,
 * instead of falling back to {@code targetLabel}'s single-value display.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AffectedUserDto {
    private UUID userId;
    private String fullName;
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.audit.AuditLogEntryDto;
import com.nforce.onehr.dto.audit.AuditLogStatsDto;
import com.nforce.onehr.service.AuditActionGroup;
import com.nforce.onehr.service.AuditQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One shared set of endpoints for both HR Admin and Super Admin — same precedent as
 * {@code AttendanceController.employeeHistory}: a single method whose scope varies by the
 * caller's actual role, rather than two endpoints duplicating the same filter wiring. The
 * HR_OPERATIONAL/ACCESS_CONTROL split is enforced entirely server-side in
 * {@link AuditQueryService} — the client never gets to ask for a role it doesn't hold.
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class AuditLogController {

    private final AuditQueryService auditQueryService;

    @GetMapping
    public Page<AuditLogEntryDto> list(
            @RequestParam(required = false) String targetSearch,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) AuditActionGroup group,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return auditQueryService.search(targetSearch, action, group, from, to,
                page, size, isSuperAdmin(authentication), authentication.getName());
    }

    @GetMapping("/stats")
    public AuditLogStatsDto stats(
            @RequestParam(required = false) String targetSearch,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Authentication authentication) {
        return auditQueryService.stats(targetSearch, action, from, to,
                isSuperAdmin(authentication), authentication.getName());
    }

    /** Unpaginated — backs the frontend's "export matches current filters exactly" requirement. */
    @GetMapping("/export")
    public List<AuditLogEntryDto> exportAll(
            @RequestParam(required = false) String targetSearch,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) AuditActionGroup group,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Authentication authentication) {
        return auditQueryService.searchAll(targetSearch, action, group, from, to,
                isSuperAdmin(authentication), authentication.getName());
    }

    private boolean isSuperAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_SUPER_ADMIN"));
    }
}

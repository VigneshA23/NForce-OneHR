package com.nforce.onehr.controller;

import com.nforce.onehr.service.AuditActionGroup;
import com.nforce.onehr.service.AuditQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trusts Spring's own {@code @PreAuthorize} enforcement (no {@code @WebMvcTest} precedent
 * elsewhere in this repo) — only the role→isSuperAdmin boolean mapping is unit-tested here.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock private AuditQueryService auditQueryService;

    @InjectMocks private AuditLogController controller;

    private Authentication authWithRole(String roleAuthority) {
        return new UsernamePasswordAuthenticationToken(
                "user@test.com", "n/a", List.of(new SimpleGrantedAuthority(roleAuthority)));
    }

    @Test
    void list_hrAdminRole_passesIsSuperAdminFalse() {
        when(auditQueryService.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), eq(false), eq("user@test.com")))
                .thenReturn(Page.empty());

        controller.list(null, null, null, null, null, 0, 20, authWithRole("ROLE_HR_ADMIN"));

        verify(auditQueryService).search(any(), any(), any(), any(), any(), anyInt(), anyInt(), eq(false), eq("user@test.com"));
    }

    @Test
    void list_superAdminRole_passesIsSuperAdminTrue() {
        when(auditQueryService.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), eq(true), eq("user@test.com")))
                .thenReturn(Page.empty());

        controller.list(null, null, null, null, null, 0, 20, authWithRole("ROLE_SUPER_ADMIN"));

        verify(auditQueryService).search(any(), any(), any(), any(), any(), anyInt(), anyInt(), eq(true), eq("user@test.com"));
    }

    @Test
    void stats_hrAdminRole_passesIsSuperAdminFalse() {
        controller.stats(null, null, null, null, authWithRole("ROLE_HR_ADMIN"));

        verify(auditQueryService).stats(any(), any(), any(), any(), eq(false), eq("user@test.com"));
    }

    @Test
    void exportAll_superAdminRole_passesIsSuperAdminTrue() {
        when(auditQueryService.searchAll(any(), any(), any(), any(), any(), eq(true), eq("user@test.com")))
                .thenReturn(List.of());

        controller.exportAll(null, null, AuditActionGroup.ACCESS, null, null, authWithRole("ROLE_SUPER_ADMIN"));

        verify(auditQueryService).searchAll(any(), any(), eq(AuditActionGroup.ACCESS), any(), any(), eq(true), eq("user@test.com"));
    }
}

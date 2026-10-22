package com.nforce.onehr.controller;

import com.nforce.onehr.entity.Role;
import com.nforce.onehr.security.SeedMatrix;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trusts Spring's own {@code @PreAuthorize} enforcement (no {@code @WebMvcTest} precedent
 * elsewhere in this repo) — only the authorities→isSuperAdmin boolean mapping is unit-tested here.
 * Authorities are built the way the request filter builds them: ROLE_&lt;CODE&gt; plus the real seeded
 * permission coverage of the role (SeedMatrix).
 */
@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock private AuditQueryService auditQueryService;

    @InjectMocks private AuditLogController controller;

    private static final Map<String, Map<String, String>> CUSTOM = Map.of(
            "CUSTOM_ACCESS_AUDITOR", Map.of("user_management:VIEW", "ALL", "audit:VIEW", "OWN"),
            "CUSTOM_AUDITOR", Map.of("audit:VIEW", "OWN", "audit:EXPORT", "OWN"));

    private Authentication authWithRole(String roleAuthority) {
        String code = roleAuthority.substring("ROLE_".length());
        Role role = code.startsWith("CUSTOM_")
                ? Role.builder().id(100).code(code).displayName(code).build()
                : SeedMatrix.role(code);
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(roleAuthority));
        SeedMatrix.permissionService(CUSTOM).coverage(List.of(role)).forEach(a -> authorities.add(new SimpleGrantedAuthority(a)));
        return new UsernamePasswordAuthenticationToken("user@test.com", "n/a", authorities);
    }

    @Test
    void list_hrAdminRole_passesIsSuperAdminFalse() {
        when(auditQueryService.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), eq(false), eq("user@test.com")))
                .thenReturn(Page.empty());

        controller.list(null, null, null, null, null, 0, 20, authWithRole("ROLE_HR_ADMIN"));

        verify(auditQueryService).search(any(), any(), any(), any(), any(), anyInt(), anyInt(), eq(false), eq("user@test.com"));
    }

    @Test
    void list_managerRole_passesIsSuperAdminFalse() {
        // Manager must be treated identically to HR Admin here — never elevated to the
        // Super Admin (ACCESS_CONTROL-visible) scope.
        when(auditQueryService.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), eq(false), eq("user@test.com")))
                .thenReturn(Page.empty());

        controller.list(null, null, null, null, null, 0, 20, authWithRole("ROLE_MANAGER"));

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
    void list_customRoleWithUserManagementView_passesIsSuperAdminTrue() {
        controller.list(null, null, null, null, null, 0, 20, authWithRole("ROLE_CUSTOM_ACCESS_AUDITOR"));

        verify(auditQueryService).search(any(), any(), any(), any(), any(), anyInt(), anyInt(), eq(true), eq("user@test.com"));
    }

    @Test
    void list_customRoleWithoutUserManagementView_passesIsSuperAdminFalse() {
        controller.list(null, null, null, null, null, 0, 20, authWithRole("ROLE_CUSTOM_AUDITOR"));

        verify(auditQueryService).search(any(), any(), any(), any(), any(), anyInt(), anyInt(), eq(false), eq("user@test.com"));
    }

    @Test
    void stats_hrAdminRole_passesIsSuperAdminFalse() {
        controller.stats(null, null, null, null, authWithRole("ROLE_HR_ADMIN"));

        verify(auditQueryService).stats(any(), any(), any(), any(), eq(false), eq("user@test.com"));
    }

    @Test
    void stats_managerRole_passesIsSuperAdminFalse() {
        controller.stats(null, null, null, null, authWithRole("ROLE_MANAGER"));

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

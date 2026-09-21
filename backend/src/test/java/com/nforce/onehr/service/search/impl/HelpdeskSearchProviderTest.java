package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.helpdesk.TicketSummaryDto;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.HelpdeskService;
import com.nforce.onehr.service.search.SearchProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Helpdesk search provider — must route Employee/Manager to {@link HelpdeskService#listMine}
 * (their own tickets only) and HR Admin/Super Admin to {@link HelpdeskService#listQueue} (the
 * full queue), the same distinction HelpDeskPage vs HelpDeskAdminPage already make. This provider
 * adds no filtering of its own, so these tests are really pinning the delegation.
 */
@ExtendWith(MockitoExtension.class)
class HelpdeskSearchProviderTest {

    @Mock private HelpdeskService helpdeskService;
    @InjectMocks private HelpdeskSearchProvider provider;

    private User userWithRole(String roleCode) {
        return User.builder().id(UUID.randomUUID()).email(roleCode.toLowerCase() + "@test.com")
                .roles(Set.of(Role.builder().code(roleCode).build())).build();
    }

    private TicketSummaryDto ticket(UUID id, String number, String employeeName, String status) {
        return TicketSummaryDto.builder().id(id).ticketNumber(number).employeeName(employeeName)
                .categoryName("Leave").status(status).build();
    }

    @Test
    void employee_seesOnlyOwnTickets_viaListMine() {
        User employee = userWithRole("EMPLOYEE");
        UUID id = UUID.randomUUID();
        when(helpdeskService.listMine(eq(employee.getEmail()), isNull(), eq("leave"), eq(0), eq(5)))
                .thenReturn(new PageImpl<>(List.of(ticket(id, "T-100", "Self", "OPEN")), org.springframework.data.domain.PageRequest.of(0, 5), 1));

        SearchProvider.SearchProviderResult result = provider.preview(employee, "leave", 5);

        assertEquals(1, result.items().size());
        assertEquals("/help?ticketId=" + id, result.items().get(0).getDetailUrl());
        verify(helpdeskService, never()).listQueue(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void manager_seesOnlyOwnTickets_sameAsEmployee() {
        User manager = userWithRole("MANAGER");
        when(helpdeskService.listMine(eq(manager.getEmail()), isNull(), eq("leave"), eq(0), eq(5)))
                .thenReturn(Page.empty());

        provider.preview(manager, "leave", 5);

        verify(helpdeskService).listMine(manager.getEmail(), null, "leave", 0, 5);
        verify(helpdeskService, never()).listQueue(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void hrAdmin_seesFullQueue_viaListQueue() {
        User hrAdmin = userWithRole("HR_ADMIN");
        UUID id = UUID.randomUUID();
        when(helpdeskService.listQueue(eq(hrAdmin.getEmail()), isNull(), isNull(), eq("leave"), eq(0), eq(5)))
                .thenReturn(new PageImpl<>(List.of(ticket(id, "T-200", "John Smith", "OPEN"))));

        SearchProvider.SearchProviderResult result = provider.preview(hrAdmin, "leave", 5);

        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).getSubtitle().contains("John Smith"));
        assertEquals("/requests?ticketId=" + id, result.items().get(0).getDetailUrl());
        verify(helpdeskService, never()).listMine(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void superAdmin_seesFullQueue_sameAsHrAdmin() {
        User superAdmin = userWithRole("SUPER_ADMIN");
        when(helpdeskService.listQueue(eq(superAdmin.getEmail()), isNull(), isNull(), eq("leave"), eq(0), eq(5)))
                .thenReturn(Page.empty());

        provider.preview(superAdmin, "leave", 5);

        verify(helpdeskService).listQueue(superAdmin.getEmail(), null, null, "leave", 0, 5);
        verify(helpdeskService, never()).listMine(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void refineUrl_admin_pointsAtRequestsQueue() {
        assertEquals("/requests?search=leave", provider.refineUrl(userWithRole("HR_ADMIN"), "leave"));
    }

    @Test
    void refineUrl_nonAdmin_pointsAtHelpDeskPage() {
        assertEquals("/help?ticketSearch=leave", provider.refineUrl(userWithRole("EMPLOYEE"), "leave"));
    }
}

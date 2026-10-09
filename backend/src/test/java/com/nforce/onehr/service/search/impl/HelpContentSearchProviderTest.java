package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.helpcontent.HelpContentSummaryDto;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.HelpContentService;
import com.nforce.onehr.service.search.SearchProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Help Content / Knowledge Base search provider — always goes through
 * {@link HelpContentService#listPublished}, which enforces the published+audience gate itself
 * (see that method's Specification chain), regardless of the viewer's role — matching how the
 * Help & Guidance page's own content search box behaves for every role today.
 */
@ExtendWith(MockitoExtension.class)
class HelpContentSearchProviderTest {

    @Mock private HelpContentService helpContentService;
    @InjectMocks private HelpContentSearchProvider provider;

    private User userWithRole(String roleCode) {
        return User.builder().id(UUID.randomUUID()).email(roleCode.toLowerCase() + "@test.com")
                .roles(Set.of(Role.builder().code(roleCode).build())).build();
    }

    @Test
    void delegatesToPublishedContentSearch_withViewerEmailForAudienceScoping() {
        User employee = userWithRole("EMPLOYEE");
        UUID id = UUID.randomUUID();
        HelpContentSummaryDto faq = HelpContentSummaryDto.builder().id(id).type("FAQ").category("Leave").title("How do I apply for leave?").build();
        when(helpContentService.listPublished(isNull(), isNull(), eq("leave"), isNull(), eq(0), eq(5), eq(employee.getEmail())))
                .thenReturn(new PageImpl<>(List.of(faq)));

        SearchProvider.SearchProviderResult result = provider.preview(employee, "leave", 5);

        assertEquals(1, result.items().size());
        assertEquals("How do I apply for leave?", result.items().get(0).getTitle());
        assertEquals("/help?contentId=" + id, result.items().get(0).getDetailUrl());
        verify(helpContentService).listPublished(null, null, "leave", null, 0, 5, employee.getEmail());
    }

    @Test
    void noMatches_returnsEmptyResult() {
        User employee = userWithRole("EMPLOYEE");
        when(helpContentService.listPublished(isNull(), isNull(), eq("zzz"), isNull(), eq(0), eq(5), eq(employee.getEmail())))
                .thenReturn(new PageImpl<>(List.of()));

        SearchProvider.SearchProviderResult result = provider.preview(employee, "zzz", 5);

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.totalCount());
    }

    @Test
    void refineUrl_pointsAtHelpAndGuidancePageContentSearch() {
        assertEquals("/help?contentSearch=leave", provider.refineUrl(userWithRole("EMPLOYEE"), "leave"));
    }
}

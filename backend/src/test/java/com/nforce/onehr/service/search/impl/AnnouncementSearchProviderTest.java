package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.doc.AnnouncementResponse;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.AnnouncementService;
import com.nforce.onehr.service.search.SearchProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * Announcements search provider — reuses {@link AnnouncementService#listPublished()}, the same
 * unscoped published/active list every authenticated user already sees, so there's no
 * authorization branching to test here (unlike Documents/Helpdesk); focus is relevance + paging.
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementSearchProviderTest {

    @Mock private AnnouncementService announcementService;
    @InjectMocks private AnnouncementSearchProvider provider;

    private User anyActor() {
        return User.builder().id(UUID.randomUUID()).email("someone@test.com")
                .roles(Set.of(Role.builder().code("EMPLOYEE").build())).build();
    }

    private AnnouncementResponse announcement(String title, String body) {
        return new AnnouncementResponse(new java.util.Random().nextLong(), title, body, "All Employees",
                null, Instant.now(), UUID.randomUUID(), Instant.now(), true, true);
    }

    @BeforeEach
    void setUp() {
        lenient().when(announcementService.listPublished()).thenReturn(List.of(
                announcement("Diwali Holiday", "Office closed for Diwali."),
                announcement("Holiday Calendar 2026", "Full year holiday calendar attached."),
                announcement("Town Hall Recap", "No mention here.")
        ));
    }

    @Test
    void ranksStartsWithAboveContains() {
        SearchProvider.SearchProviderResult result = provider.preview(anyActor(), "holiday", 10);

        assertEquals(2, result.totalCount());
        List<String> order = result.items().stream().map(i -> i.getTitle()).toList();
        assertEquals(List.of("Holiday Calendar 2026", "Diwali Holiday"), order);
    }

    @Test
    void noMatches_returnsEmptyResult() {
        SearchProvider.SearchProviderResult result = provider.preview(anyActor(), "zzz-nomatch", 10);
        assertTrue(result.items().isEmpty());
        assertEquals(0, result.totalCount());
    }

    @Test
    void refineUrl_pointsAtAnnouncementsTabOfMyDocuments() {
        assertEquals("/my-documents?tab=announcements&search=holiday", provider.refineUrl(anyActor(), "holiday"));
    }
}

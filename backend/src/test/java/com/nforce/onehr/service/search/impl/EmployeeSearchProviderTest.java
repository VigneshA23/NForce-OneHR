package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.search.SearchResultItem;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.EmployeeService;
import com.nforce.onehr.service.search.SearchProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * Employees/Directory search provider — same visibility as {@code /api/employees/directory}
 * (org-wide, any authenticated role), so the interesting behavior here is relevance ordering and
 * pagination, not authorization branching (see the class javadoc on EmployeeSearchProvider).
 */
@ExtendWith(MockitoExtension.class)
class EmployeeSearchProviderTest {

    @Mock private EmployeeService employeeService;
    @InjectMocks private EmployeeSearchProvider provider;

    private User anyActor() {
        return User.builder().id(UUID.randomUUID()).email("someone@test.com")
                .roles(Set.of(Role.builder().code("EMPLOYEE").build())).build();
    }

    private DirectoryEntryDto entry(String userId, String name, String code) {
        return DirectoryEntryDto.builder().userId(userId).fullName(name).employeeCode(code)
                .email(name.toLowerCase().replace(" ", ".") + "@test.com").active(true).build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(employeeService.listDirectory()).thenReturn(List.of(
                entry("u-johnson", "Johnson Lee", "NF-100"),      // contains "john"
                entry("u-john-doe", "John Doe", "NF-200"),        // starts-with "john"
                entry("u-exact", "John", "NF-300"),               // exact match
                entry("u-unrelated", "Priya Rao", "NF-400")       // no match
        ));
    }

    @Test
    void ranksExactMatchAboveStartsWithAboveContains() {
        SearchProvider.SearchProviderResult result = provider.preview(anyActor(), "john", 10);

        assertEquals(3, result.totalCount());
        List<String> order = result.items().stream().map(SearchResultItem::getTitle).toList();
        assertEquals(List.of("John", "John Doe", "Johnson Lee"), order);
    }

    @Test
    void excludesNonMatchingRecords() {
        SearchProvider.SearchProviderResult result = provider.preview(anyActor(), "john", 10);
        assertTrue(result.items().stream().noneMatch(i -> i.getTitle().equals("Priya Rao")));
    }

    @Test
    void noMatches_returnsEmptyResultNotAnError() {
        SearchProvider.SearchProviderResult result = provider.preview(anyActor(), "zzz-nomatch", 10);
        assertTrue(result.items().isEmpty());
        assertEquals(0, result.totalCount());
    }

    @Test
    void pagination_secondPageOfTwo_returnsRemainingMatchOnly() {
        SearchProvider.SearchProviderResult page0 = provider.page(anyActor(), "john", 0, 2);
        SearchProvider.SearchProviderResult page1 = provider.page(anyActor(), "john", 1, 2);

        assertEquals(2, page0.items().size());
        assertEquals(1, page1.items().size());
        assertEquals(3, page0.totalCount());
        assertEquals(3, page1.totalCount());
    }

    @Test
    void detailUrl_pointsAtExistingDirectoryDeepLink() {
        SearchResultItem item = provider.preview(anyActor(), "john", 10).items().get(0);
        assertEquals("/directory?userId=u-exact", item.getDetailUrl());
    }

    @Test
    void refineUrl_pointsAtDirectoryPageWithSearchTerm() {
        assertEquals("/directory?search=john", provider.refineUrl(anyActor(), "john"));
    }
}

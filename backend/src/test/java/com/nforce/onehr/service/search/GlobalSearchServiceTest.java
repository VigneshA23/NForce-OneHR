package com.nforce.onehr.service.search;

import com.nforce.onehr.dto.search.SearchGroupDto;
import com.nforce.onehr.dto.search.SearchPageResponse;
import com.nforce.onehr.dto.search.SearchPreviewResponse;
import com.nforce.onehr.dto.search.SearchResultItem;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests (same convention as HelpdeskServiceTest/ReportsControllerTest) —
 * providers themselves are mocked, so this focuses purely on GlobalSearchService's own
 * responsibilities: query validation, fanning out to every registered provider, skipping empty
 * groups, and routing {@code /{module}} to the right provider with independent pagination.
 */
@ExtendWith(MockitoExtension.class)
class GlobalSearchServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private SearchProvider employeesProvider;
    @Mock private SearchProvider documentsProvider;

    private GlobalSearchService service;

    private final String actorEmail = "employee@test.com";
    private User actorUser;

    @BeforeEach
    void setUp() {
        Role role = Role.builder().code("EMPLOYEE").build();
        actorUser = User.builder().id(UUID.randomUUID()).email(actorEmail).roles(Set.of(role)).build();

        lenient().when(employeesProvider.moduleKey()).thenReturn("EMPLOYEES");
        lenient().when(employeesProvider.moduleLabel()).thenReturn("Employees");
        lenient().when(documentsProvider.moduleKey()).thenReturn("DOCUMENTS");
        lenient().when(documentsProvider.moduleLabel()).thenReturn("Documents");

        service = new GlobalSearchService(List.of(employeesProvider, documentsProvider), userRepo);
    }

    private void stubUser() {
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(actorUser));
    }

    // ── Query validation ────────────────────────────────────

    @Test
    void preview_rejectsQueryShorterThanMinimum() {
        assertThrows(IllegalArgumentException.class, () -> service.preview(actorEmail, "j"));
        verifyNoInteractions(userRepo, employeesProvider, documentsProvider);
    }

    @Test
    void preview_rejectsBlankQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.preview(actorEmail, "   "));
    }

    @Test
    void preview_rejectsNullQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.preview(actorEmail, null));
    }

    @Test
    void preview_rejectsQueryLongerThanMaximum() {
        String tooLong = "a".repeat(GlobalSearchService.MAX_QUERY_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> service.preview(actorEmail, tooLong));
    }

    @Test
    void preview_acceptsQueryAtExactlyMaximumLength() {
        stubUser();
        String maxLen = "a".repeat(GlobalSearchService.MAX_QUERY_LENGTH);
        when(employeesProvider.preview(eq(actorUser), eq(maxLen), anyInt())).thenReturn(SearchProvider.SearchProviderResult.EMPTY);
        when(documentsProvider.preview(eq(actorUser), eq(maxLen), anyInt())).thenReturn(SearchProvider.SearchProviderResult.EMPTY);

        assertDoesNotThrow(() -> service.preview(actorEmail, maxLen));
    }

    @Test
    void preview_trimsWhitespaceBeforeValidatingAndDispatching() {
        stubUser();
        when(employeesProvider.preview(eq(actorUser), eq("john"), anyInt())).thenReturn(SearchProvider.SearchProviderResult.EMPTY);
        when(documentsProvider.preview(eq(actorUser), eq("john"), anyInt())).thenReturn(SearchProvider.SearchProviderResult.EMPTY);

        SearchPreviewResponse response = service.preview(actorEmail, "  john  ");

        assertEquals("john", response.getQuery());
        verify(employeesProvider).preview(actorUser, "john", 5);
    }

    // ── Grouping / no results ────────────────────────────────

    @Test
    void preview_omitsModulesWithNoMatches_noResultsMeansNoGroupNotAnEmptyGroup() {
        stubUser();
        when(employeesProvider.preview(any(), any(), anyInt())).thenReturn(SearchProvider.SearchProviderResult.EMPTY);
        when(documentsProvider.preview(any(), any(), anyInt())).thenReturn(SearchProvider.SearchProviderResult.EMPTY);

        SearchPreviewResponse response = service.preview(actorEmail, "zzz");

        assertTrue(response.getGroups().isEmpty());
    }

    @Test
    void preview_groupsResultsByModule_onlyNonEmptyModulesIncluded() {
        stubUser();
        SearchResultItem johnSmith = SearchResultItem.builder().module("EMPLOYEES").id("u1").title("John Smith").build();
        when(employeesProvider.preview(any(), any(), anyInt()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(johnSmith), 1));
        when(employeesProvider.refineUrl(any(), any())).thenReturn("/directory?search=john");
        when(documentsProvider.preview(any(), any(), anyInt())).thenReturn(SearchProvider.SearchProviderResult.EMPTY);

        SearchPreviewResponse response = service.preview(actorEmail, "john");

        assertEquals(1, response.getGroups().size());
        SearchGroupDto group = response.getGroups().get(0);
        assertEquals("EMPLOYEES", group.getModule());
        assertEquals(1, group.getTotalCount());
        assertEquals("John Smith", group.getItems().get(0).getTitle());
        assertEquals("/directory?search=john", group.getRefineUrl());
    }

    // ── Per-module page / pagination isolation ───────────────

    @Test
    void modulePage_routesToTheRequestedModuleOnly_caseInsensitive() {
        stubUser();
        when(documentsProvider.page(eq(actorUser), eq("john"), eq(1), eq(10)))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 25));
        when(documentsProvider.refineUrl(any(), any())).thenReturn("/documents?search=john");

        SearchPageResponse response = service.modulePage(actorEmail, "documents", "john", 1, 10);

        assertEquals("DOCUMENTS", response.getModule());
        assertEquals(1, response.getPage());
        assertEquals(25, response.getTotalElements());
        assertEquals(3, response.getTotalPages());
        verify(documentsProvider).page(actorUser, "john", 1, 10);
        verify(employeesProvider, never()).page(any(), any(), anyInt(), anyInt());
    }

    @Test
    void modulePage_unknownModule_throwsNotFound() {
        stubUser();
        assertThrows(NoSuchElementException.class, () -> service.modulePage(actorEmail, "NOT_A_MODULE", "john", 0, 10));
    }

    @Test
    void modulePage_clampsPageSize_toConfiguredMaximum() {
        stubUser();
        when(documentsProvider.page(any(), any(), anyInt(), eq(50)))
                .thenReturn(SearchProvider.SearchProviderResult.EMPTY);

        service.modulePage(actorEmail, "DOCUMENTS", "john", 0, 1000);

        verify(documentsProvider).page(actorUser, "john", 0, 50);
    }

    @Test
    void modulePage_negativePage_clampedToZero() {
        stubUser();
        when(documentsProvider.page(any(), any(), eq(0), anyInt()))
                .thenReturn(SearchProvider.SearchProviderResult.EMPTY);

        service.modulePage(actorEmail, "DOCUMENTS", "john", -5, 10);

        verify(documentsProvider).page(actorUser, "john", 0, 10);
    }

    /**
     * Two independently-paginated groups (e.g. Employees on page 2 while Documents stays on page
     * 0) are just two separate {@code modulePage} calls from the frontend — verifying each call is
     * scoped to only its own provider is what guarantees they can never affect each other.
     */
    @Test
    void modulePage_twoModulesPaginatedIndependently_eachCallOnlyTouchesItsOwnProvider() {
        stubUser();
        when(employeesProvider.page(eq(actorUser), eq("john"), eq(2), eq(10)))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 40));
        when(employeesProvider.refineUrl(any(), any())).thenReturn("/directory?search=john");
        when(documentsProvider.page(eq(actorUser), eq("john"), eq(0), eq(10)))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 3));
        when(documentsProvider.refineUrl(any(), any())).thenReturn("/documents?search=john");

        service.modulePage(actorEmail, "EMPLOYEES", "john", 2, 10);
        service.modulePage(actorEmail, "DOCUMENTS", "john", 0, 10);

        verify(employeesProvider).page(actorUser, "john", 2, 10);
        verify(documentsProvider).page(actorUser, "john", 0, 10);
        verify(employeesProvider, never()).page(any(), any(), eq(0), anyInt());
    }
}

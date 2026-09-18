package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.search.SearchResultItem;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.EmployeeService;
import com.nforce.onehr.service.search.RelevanceScorer;
import com.nforce.onehr.service.search.SearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.nforce.onehr.service.search.SearchSupport.encode;
import static com.nforce.onehr.service.search.SearchSupport.paginate;

/**
 * Employees/Directory — backed by {@code /api/employees/directory}, the same org-wide, work-info
 * -only listing every authenticated user already sees on the People Directory page (no manager/
 * HR scoping to replicate here; {@link EmployeeService#listDirectory()} is that single source of
 * truth). Name/code matches rank above a generic contains-match, per the story's relevance rule.
 */
@Component
@RequiredArgsConstructor
public class EmployeeSearchProvider implements SearchProvider {

    private final EmployeeService employeeService;

    @Override
    public String moduleKey() {
        return "EMPLOYEES";
    }

    @Override
    public String moduleLabel() {
        return "Employees";
    }

    @Override
    public SearchProviderResult preview(User actor, String query, int limit) {
        return search(query, 0, limit);
    }

    @Override
    public SearchProviderResult page(User actor, String query, int page, int size) {
        return search(query, page, size);
    }

    @Override
    public String refineUrl(User actor, String query) {
        return "/directory?search=" + encode(query);
    }

    private SearchProviderResult search(String query, int page, int size) {
        List<DirectoryEntryDto> ranked = employeeService.listDirectory().stream()
                .map(e -> new Scored<>(e, RelevanceScorer.bestScore(query, e.getFullName(), e.getEmployeeCode(), e.getEmail())))
                .filter(s -> s.score > 0)
                .sorted(Comparator.<Scored<DirectoryEntryDto>>comparingInt(s -> s.score).reversed()
                        .thenComparing(s -> s.value.getFullName(), Comparator.nullsLast(Comparator.naturalOrder())))
                .map(s -> s.value)
                .collect(Collectors.toList());

        List<SearchResultItem> items = paginate(ranked, page, size).stream()
                .map(e -> SearchResultItem.builder()
                        .module(moduleKey())
                        .id(e.getUserId())
                        .title(e.getFullName())
                        .subtitle(subtitleFor(e))
                        .detailUrl("/directory?userId=" + e.getUserId())
                        .build())
                .collect(Collectors.toList());
        return new SearchProviderResult(items, ranked.size());
    }

    private String subtitleFor(DirectoryEntryDto e) {
        StringBuilder sb = new StringBuilder(e.getEmployeeCode());
        if (e.getDesignationName() != null) sb.append(" · ").append(e.getDesignationName());
        if (e.getDepartmentName() != null) sb.append(" · ").append(e.getDepartmentName());
        return sb.toString();
    }

    private record Scored<T>(T value, int score) {
    }
}

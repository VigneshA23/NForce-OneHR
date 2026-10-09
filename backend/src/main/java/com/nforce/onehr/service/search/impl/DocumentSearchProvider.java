package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.doc.EmployeeDocumentResponse;
import com.nforce.onehr.dto.search.SearchResultItem;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.DocumentService;
import com.nforce.onehr.service.search.RelevanceScorer;
import com.nforce.onehr.service.search.SearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nforce.onehr.service.search.SearchSupport.encode;
import static com.nforce.onehr.service.search.SearchSupport.paginate;

/**
 * Documents — reuses {@link DocumentService#myDocuments} (Employee/Manager: their own uploads
 * only) or {@link DocumentService#listAll} (HR Admin/Super Admin: every employee's documents),
 * exactly the same role-scoped source each role's existing Documents page already fetches. Text
 * matching/pagination happen here because neither existing endpoint takes a search param, but the
 * underlying record set is never wider than what that role could already list.
 */
@Component
@RequiredArgsConstructor
public class DocumentSearchProvider implements SearchProvider {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final DocumentService documentService;

    @Override
    public String moduleKey() {
        return "DOCUMENTS";
    }

    @Override
    public String moduleLabel() {
        return "Documents";
    }

    @Override
    public SearchProviderResult preview(User actor, String query, int limit) {
        return search(actor, query, 0, limit);
    }

    @Override
    public SearchProviderResult page(User actor, String query, int page, int size) {
        return search(actor, query, page, size);
    }

    @Override
    public String refineUrl(User actor, String query) {
        return isAdmin(actor)
                ? "/documents?search=" + encode(query)
                : "/my-documents?tab=docs&search=" + encode(query);
    }

    private boolean isAdmin(User actor) {
        return actor.getRoles().stream().map(Role::getCode).anyMatch(ADMIN_ROLES::contains);
    }

    private SearchProviderResult search(User actor, String query, int page, int size) {
        boolean admin = isAdmin(actor);
        List<EmployeeDocumentResponse> source = admin
                ? documentService.listAll(actor.getEmail())
                : documentService.myDocuments(actor.getEmail());

        List<Scored> ranked = source.stream()
                .map(d -> new Scored(d, RelevanceScorer.bestScore(query, d.getDocumentTypeName(), d.getFileName(), d.getEmployeeName())))
                .filter(s -> s.score > 0)
                .sorted(Comparator.<Scored>comparingInt(s -> s.score).reversed())
                .collect(Collectors.toList());

        List<SearchResultItem> items = paginate(ranked, page, size).stream()
                .map(s -> {
                    EmployeeDocumentResponse d = s.doc;
                    String url = admin
                            ? "/documents?search=" + encode(d.getDocumentTypeName())
                            : "/my-documents?tab=docs&search=" + encode(d.getDocumentTypeName());
                    return SearchResultItem.builder()
                            .module(moduleKey())
                            .id(d.getId().toString())
                            .title(d.getDocumentTypeName())
                            .subtitle(subtitleFor(d, admin))
                            .detailUrl(url)
                            .build();
                })
                .collect(Collectors.toList());
        return new SearchProviderResult(items, ranked.size());
    }

    private String subtitleFor(EmployeeDocumentResponse d, boolean admin) {
        StringBuilder sb = new StringBuilder();
        if (admin && d.getEmployeeName() != null) sb.append(d.getEmployeeName()).append(" · ");
        sb.append(d.getStatus());
        return sb.toString();
    }

    private record Scored(EmployeeDocumentResponse doc, int score) {
    }
}

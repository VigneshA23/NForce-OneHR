package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.helpdesk.TicketSummaryDto;
import com.nforce.onehr.dto.search.SearchResultItem;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.HelpdeskService;
import com.nforce.onehr.service.search.SearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nforce.onehr.service.search.SearchSupport.encode;

/**
 * Helpdesk tickets — delegates straight to {@link HelpdeskService#listMine} (Employee/Manager:
 * their own tickets) or {@link HelpdeskService#listQueue} (HR Admin/Super Admin: the full queue),
 * the exact same paginated, already-authorized search each role's Help Desk view already calls —
 * this provider adds no filtering of its own.
 */
@Component
@RequiredArgsConstructor
public class HelpdeskSearchProvider implements SearchProvider {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final HelpdeskService helpdeskService;

    @Override
    public String moduleKey() {
        return "HELPDESK";
    }

    @Override
    public String moduleLabel() {
        return "Helpdesk";
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
                ? "/requests?search=" + encode(query)
                : "/help?ticketSearch=" + encode(query);
    }

    private boolean isAdmin(User actor) {
        return actor.getRoles().stream().map(Role::getCode).anyMatch(ADMIN_ROLES::contains);
    }

    private SearchProviderResult search(User actor, String query, int page, int size) {
        boolean admin = isAdmin(actor);
        Page<TicketSummaryDto> result = admin
                ? helpdeskService.listQueue(actor.getEmail(), null, null, query, page, size)
                : helpdeskService.listMine(actor.getEmail(), null, query, page, size);

        List<SearchResultItem> items = result.getContent().stream()
                .map(t -> SearchResultItem.builder()
                        .module(moduleKey())
                        .id(t.getId().toString())
                        .title("Ticket #" + t.getTicketNumber())
                        .subtitle(subtitleFor(t, admin))
                        .detailUrl(admin ? "/requests?ticketId=" + t.getId() : "/help?ticketId=" + t.getId())
                        .build())
                .collect(Collectors.toList());
        return new SearchProviderResult(items, result.getTotalElements());
    }

    private String subtitleFor(TicketSummaryDto t, boolean admin) {
        StringBuilder sb = new StringBuilder();
        if (admin && t.getEmployeeName() != null) sb.append(t.getEmployeeName()).append(" · ");
        if (t.getCategoryName() != null) sb.append(t.getCategoryName()).append(" · ");
        sb.append(t.getStatus());
        return sb.toString();
    }
}

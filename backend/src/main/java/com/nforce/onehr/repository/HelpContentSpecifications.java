package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpContent;
import org.springframework.data.jpa.domain.Specification;

/**
 * Specification factory for {@link HelpContentRepository}, mirroring
 * {@code HelpdeskTicketSpecifications}: each factory returns an always-true predicate when its
 * filter isn't supplied, so callers chain every filter unconditionally with
 * {@code Specification.allOf(...)}.
 */
public final class HelpContentSpecifications {

    private HelpContentSpecifications() {
    }

    public static Specification<HelpContent> typeIs(String type) {
        if (type == null || type.isBlank()) return (root, query, cb) -> cb.conjunction();
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<HelpContent> categoryIs(String category) {
        if (category == null || category.isBlank()) return (root, query, cb) -> cb.conjunction();
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    /** Employee-visible content only. */
    public static Specification<HelpContent> publishedAndActive() {
        return (root, query, cb) -> cb.equal(root.get("status"), "PUBLISHED");
    }

    /** Matches on title, description, or body text (case-insensitive, partial). */
    public static Specification<HelpContent> searchText(String search) {
        if (search == null || search.isBlank()) return (root, query, cb) -> cb.conjunction();
        String like = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), like),
                cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like),
                cb.like(cb.lower(cb.coalesce(root.get("body"), "")), like)
        );
    }
}

package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpContent;
import com.nforce.onehr.entity.HelpContentAudience;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.Set;
import java.util.UUID;

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

    /**
     * Admin list/detail gate: a DRAFT is private to whoever created it — no other HR Admin/Super
     * Admin, however privileged, may see it. Every other status (PENDING_APPROVAL, APPROVED,
     * PUBLISHED, UNPUBLISHED, ARCHIVED) is left untouched by this predicate, so the existing
     * submit-for-review/approval-center visibility (any admin can see submitted content) is
     * preserved unchanged — only the DRAFT state is newly restricted.
     */
    public static Specification<HelpContent> draftVisibleOnlyToCreator(UUID viewerId) {
        return (root, query, cb) -> cb.or(
                cb.notEqual(root.get("status"), "DRAFT"),
                cb.equal(root.get("createdBy"), viewerId));
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

    /**
     * Employee-facing audience gate: visible to {@code viewerBuckets} if the content has no
     * audience tags at all (published before this feature, or never explicitly targeted — same
     * "visible to everyone" meaning as today's unfiltered behavior), or if at least one of its
     * tags is in the viewer's bucket set (see {@code RoleUtils#audienceBuckets} — a Manager's
     * bucket set is {EMPLOYEE, MANAGER}, since they hold both roles for real, not a hardcoded
     * hierarchy). An EXISTS-correlated-subquery predicate, composing with the rest of this
     * class's Specification.allOf(...) chain the same way a plain equality filter would.
     */
    public static Specification<HelpContent> audienceVisibleTo(Set<String> viewerBuckets) {
        return (root, query, cb) -> {
            Subquery<UUID> anyTag = query.subquery(UUID.class);
            var anyTagRoot = anyTag.from(HelpContentAudience.class);
            anyTag.select(anyTagRoot.get("id")).where(cb.equal(anyTagRoot.get("contentId"), root.get("id")));

            Subquery<UUID> matchingTag = query.subquery(UUID.class);
            var matchingTagRoot = matchingTag.from(HelpContentAudience.class);
            matchingTag.select(matchingTagRoot.get("id")).where(cb.and(
                    cb.equal(matchingTagRoot.get("contentId"), root.get("id")),
                    matchingTagRoot.get("audience").in(viewerBuckets)
            ));

            return cb.or(cb.not(cb.exists(anyTag)), cb.exists(matchingTag));
        };
    }
}

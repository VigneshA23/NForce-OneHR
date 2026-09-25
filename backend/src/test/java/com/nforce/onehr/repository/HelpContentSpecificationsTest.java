package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpContent;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test of the Criteria {@link Predicate} construction in
 * {@link HelpContentSpecifications#draftVisibleOnlyToCreator}, mocking the JPA Criteria API
 * (Root/CriteriaBuilder) rather than exercising a real query. A genuine {@code @DataJpaTest}
 * against this entity graph isn't viable in this repo's H2 test profile — even {@link HelpContent}
 * itself uses Postgres-only {@code columnDefinition}s (e.g. {@code TIMESTAMPTZ}) that H2 doesn't
 * recognize as a type, independent of the citext issue {@code LeaveServiceTest}'s header comment
 * describes — so this predicate-construction test is the closest meaningful unit-level coverage,
 * consistent with {@code HelpContentServiceTest}'s stated avoidance of testing Specification-based
 * filtering against a mock repository.
 */
@ExtendWith(MockitoExtension.class)
class HelpContentSpecificationsTest {

    @Mock private Root<HelpContent> root;
    @Mock private CriteriaQuery<?> query;
    @Mock private CriteriaBuilder cb;

    @Test
    @SuppressWarnings("unchecked")
    void draftVisibleOnlyToCreator_isNonDraftStatusOrCreatedByTheViewer() {
        UUID viewerId = UUID.randomUUID();
        Path<Object> statusPath = org.mockito.Mockito.mock(Path.class);
        Path<Object> createdByPath = org.mockito.Mockito.mock(Path.class);
        Predicate notDraft = org.mockito.Mockito.mock(Predicate.class);
        Predicate ownedByViewer = org.mockito.Mockito.mock(Predicate.class);
        Predicate combined = org.mockito.Mockito.mock(Predicate.class);

        when(root.get("status")).thenReturn(statusPath);
        when(root.get("createdBy")).thenReturn(createdByPath);
        when(cb.notEqual(statusPath, "DRAFT")).thenReturn(notDraft);
        when(cb.equal(createdByPath, viewerId)).thenReturn(ownedByViewer);
        when(cb.or(notDraft, ownedByViewer)).thenReturn(combined);

        Specification<HelpContent> spec = HelpContentSpecifications.draftVisibleOnlyToCreator(viewerId);
        Predicate result = spec.toPredicate(root, query, cb);

        // Non-DRAFT statuses (PENDING_APPROVAL/APPROVED/PUBLISHED/UNPUBLISHED/ARCHIVED) must stay
        // untouched by this gate — proven here by asserting the predicate is "status != DRAFT OR
        // createdBy = viewer", not e.g. "status = PUBLISHED OR createdBy = viewer".
        assertSame(combined, result);
        verify(cb).notEqual(statusPath, "DRAFT");
        verify(cb).equal(createdByPath, viewerId);
        verify(cb).or(notDraft, ownedByViewer);
    }
}

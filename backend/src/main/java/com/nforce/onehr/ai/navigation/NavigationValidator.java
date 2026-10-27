package com.nforce.onehr.ai.navigation;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.NavigationAction;
import com.nforce.onehr.ai.contract.PageReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Decides whether a navigation target proposed by the model may be shown to this user.
 *
 * <p>This is the whole of the "never let the model invent a route" guarantee, so it is deliberately
 * an allowlist with no escape hatch: a target survives only by matching a registry entry for the
 * caller's own shell role. Everything else is dropped.
 *
 * <p>The frontend is not a second line of defence here. Shell resolves the current nav item with
 * {@code navItems.find(n => path.startsWith(n.path)) ?? navItems[0]} and then renders the outlet
 * whenever that item is not a Phase 2 placeholder. So a path a role has no nav entry for falls back
 * to the dashboard item, is not disabled, and <em>the real page still renders</em>. If an
 * unauthorised pageId got past this class, the user would be dropped on a page with no matching
 * sidebar entry rather than being stopped. Validation has to be right here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationValidator {

    private final PageRegistry pageRegistry;

    /**
     * Validates a model-proposed pageId and rebuilds the action from registry data.
     *
     * <p>Note this never trusts the model's own {@code label}: it is discarded and replaced with
     * the registry label for this role. Otherwise the model could describe the button as something
     * the page is not, which matters most for the role-polymorphic pages where the same route is
     * legitimately called two different things.
     *
     * @return a validated action, or empty when the target must be dropped
     */
    public Optional<NavigationAction> validate(String proposedPageId, AssistantRequestContext context) {
        if (proposedPageId == null || proposedPageId.isBlank() || context == null) {
            return Optional.empty();
        }
        String pageId = proposedPageId.trim();

        if (context.getShellRole() == null) {
            // No resolved role means no way to know what this user can reach. Fail closed.
            log.warn("Dropping navigation to '{}': request context has no shell role", pageId);
            return Optional.empty();
        }

        Optional<PageReference> match = pageRegistry.find(pageId, context.getShellRole());
        if (match.isEmpty()) {
            // Two different causes, deliberately logged apart: an unknown id means the model
            // invented something, which is a knowledge/prompt problem; a known id the role cannot
            // reach means retrieval surfaced cross-role content, which is a filtering problem.
            if (pageRegistry.exists(pageId)) {
                log.debug("Dropping navigation to '{}': not reachable by {}", pageId, context.getShellRole());
            } else {
                log.info("Dropping navigation to unknown pageId '{}' proposed by the model", pageId);
            }
            return Optional.empty();
        }

        PageReference page = match.get();
        if (page.isPlaceholder()) {
            // A Phase 2 item renders a "ships in a future phase" placeholder. Offering it as a
            // destination would send the user somewhere that cannot do what they asked for.
            log.debug("Dropping navigation to '{}': roadmap placeholder, not a usable page", pageId);
            return Optional.empty();
        }

        return Optional.of(NavigationAction.builder()
                .pageId(page.getPageId())
                .label(page.getLabel())
                .build());
    }

    /**
     * The name this caller's sidebar gives a page, for rewriting a page id the model wrote into
     * its text. Silent on a miss, unlike {@link #validate}: most words it is asked about are not
     * page ids at all, and that is not the model inventing a destination.
     */
    public Optional<String> labelFor(String pageId, AssistantRequestContext context) {
        if (pageId == null || context == null || context.getShellRole() == null) return Optional.empty();
        return pageRegistry.find(pageId, context.getShellRole()).map(PageReference::getLabel);
    }

    /**
     * Validates the client-supplied current page, used only as a retrieval hint.
     *
     * <p>Unlike {@link #validate}, a placeholder is acceptable here: a user genuinely can be
     * sitting on a Phase 2 placeholder page, and knowing that is useful context for answering
     * "what is this?" It is still dropped if unknown or not reachable by this role, so a spoofed
     * value can at worst bias ranking and can never widen what is retrieved.
     */
    public Optional<PageReference> validateCurrentPage(String pageId, AssistantRequestContext context) {
        if (pageId == null || pageId.isBlank() || context == null || context.getShellRole() == null) {
            return Optional.empty();
        }
        return pageRegistry.find(pageId.trim(), context.getShellRole());
    }
}

package com.nforce.onehr.ai.prompt;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.PageReference;
import com.nforce.onehr.ai.contract.RetrievalResult;
import com.nforce.onehr.ai.navigation.PageRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Assembles the system and user prompts for one turn.
 *
 * <p>Two things here are load-bearing rather than cosmetic.
 *
 * <p><strong>Retrieved knowledge is fenced in tags and declared to be data.</strong> Help and
 * Guidance content is authored by HR Admins, so this is not defence against a hostile author so
 * much as against ordinary content: an FAQ that legitimately quotes an error message, or a guide
 * that says "type the following into the box", reads exactly like an instruction once it is
 * flattened into a prompt. Tagging it keeps the boundary explicit.
 *
 * <p><strong>The reachable-pages list is scoped to the caller's own role.</strong> The model is
 * never shown pages this user cannot open, so the most likely way to produce a bad navigation -
 * seeing a plausible page and picking it - is removed before it can happen. NavigationValidator
 * still re-checks the result independently; this just means it rarely has to reject anything.
 */
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final PageRegistry pageRegistry;

    /** Builds the system prompt: standing policy, who is asking, what they can reach, what we know. */
    public String buildSystemPrompt(AssistantRequestContext context,
                                    List<RetrievalResult> knowledge,
                                    Optional<PageReference> currentPage) {
        StringBuilder sb = new StringBuilder(SystemPromptTemplate.POLICY);

        sb.append("\n\nSIGNED-IN USER\n");
        sb.append("- Role: ").append(describeRole(context)).append('\n');
        currentPage.ifPresent(page -> sb
                .append("- Currently viewing: ").append(page.getLabel())
                .append(" (pageId ").append(page.getPageId()).append(")\n")
                .append("  Use this only to resolve vague references like \"this page\" or \"here\". ")
                .append("Do not assume the question is about it.\n"));

        sb.append("\nREACHABLE PAGES\n");
        List<PageReference> pages = pageRegistry.forRole(context.getShellRole()).stream()
                .filter(p -> !p.isPlaceholder())
                .toList();
        if (pages.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (PageReference page : pages) {
                sb.append("- ").append(page.getPageId())
                        .append(" : ").append(page.getLabel())
                        .append(" — ").append(page.getDescription()).append('\n');
            }
        }
        // Named explicitly rather than silently omitted: the user can see these in their own
        // sidebar, so "that does not exist" would be visibly wrong. The model needs to be able to
        // say "it is on the roadmap" without offering to take them there.
        List<PageReference> placeholders = pageRegistry.forRole(context.getShellRole()).stream()
                .filter(PageReference::isPlaceholder)
                .toList();
        if (!placeholders.isEmpty()) {
            sb.append("\nNOT YET AVAILABLE (visible in the sidebar but shows a roadmap placeholder; ")
                    .append("describe if asked, never navigate to)\n");
            for (PageReference page : placeholders) {
                sb.append("- ").append(page.getLabel()).append('\n');
            }
        }

        sb.append("\nKNOWLEDGE\n");
        if (knowledge == null || knowledge.isEmpty()) {
            // Should not arise - the service short-circuits to UNKNOWN before calling the model
            // when retrieval is empty - but if it ever did, saying so beats an empty heading that
            // invites the model to fall back on general knowledge.
            sb.append("(no OneHR knowledge was retrieved for this question; answer with type UNKNOWN)\n");
        } else {
            for (RetrievalResult k : knowledge) {
                sb.append("<knowledge id=\"").append(k.getKnowledgeId()).append('"')
                        .append(" type=\"").append(k.getType() == null ? "UNKNOWN" : k.getType().name()).append('"');
                if (k.getModule() != null) sb.append(" module=\"").append(k.getModule()).append('"');
                if (k.getPageId() != null) sb.append(" pageId=\"").append(k.getPageId()).append('"');
                sb.append(">\n");
                if (k.getTitle() != null) sb.append(fence(k.getTitle())).append('\n');
                sb.append(fence(k.getBody())).append('\n');
                sb.append("</knowledge>\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * Builds the user prompt: bounded prior turns, then the question.
     *
     * <p>History is included as plain transcript and never as instructions. It is also the reason
     * authorisation is recomputed from the database on every request rather than carried forward -
     * an earlier turn must not be able to establish a role or a permission that a later turn then
     * relies on.
     */
    public String buildUserPrompt(String question, List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder("EARLIER IN THIS CONVERSATION\n");
        for (ConversationTurn turn : history) {
            sb.append("User: ").append(turn.question()).append('\n');
            sb.append("Assistant: ").append(turn.answer()).append("\n\n");
        }
        sb.append("CURRENT QUESTION\n").append(question);
        return sb.toString();
    }

    /**
     * Stops retrieved text from closing the tag it is wrapped in.
     *
     * <p>Without this, a body containing a literal closing knowledge tag ends the fence early, and
     * everything after it reads to the model as prompt rather than as data - the one way a
     * knowledge unit can promote itself from content to instruction. Help and Guidance titles and
     * bodies are typed into a form by a person, so this is the realistic path, and it is worth
     * closing even though the authors are HR Admins: an article that legitimately explains how the
     * assistant fences content would otherwise break its own fence.
     *
     * <p>The marker is defanged rather than deleted, so a quoted tag still reads as one and the
     * content keeps its meaning.
     */
    private String fence(String text) {
        if (text == null) return "";
        return text.replace("</knowledge", "&lt;/knowledge").replace("<knowledge", "&lt;knowledge");
    }

    private String describeRole(AssistantRequestContext context) {
        return switch (context.getShellRole()) {
            case SUPER_ADMIN -> "Super Admin (full administrative access)";
            case HR_ADMIN -> "HR Admin (organisation-wide HR administration)";
            case MANAGER -> "Manager (their own records plus their direct reports)";
            case EMPLOYEE -> "Employee (their own records only)";
        };
    }

    /** One prior exchange, trimmed to what the model needs to follow a follow-up question. */
    public record ConversationTurn(String question, String answer) {}
}

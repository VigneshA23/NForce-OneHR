package com.nforce.onehr.ai.response;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AssistantResponse;
import com.nforce.onehr.ai.contract.AssistantResponseType;
import com.nforce.onehr.ai.contract.ConfidenceLevel;
import com.nforce.onehr.ai.contract.NavigationAction;
import com.nforce.onehr.ai.navigation.NavigationValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Every way the assistant declines to answer, in one place.
 *
 * <p>These exist so that "I could not answer" is a deliberate, uniform product behaviour rather
 * than whatever each failure path happens to produce. All of them are returned with HTTP 200 and a
 * valid {@link AssistantResponse}: a provider outage or a parse failure is not the user's error,
 * and surfacing it as a 4xx/5xx would both look broken and, for an empty-bodied 403, trip
 * {@code authFetch.ts} into treating it as a dead session and signing the user out.
 *
 * <p>Each message says what happened in the user's terms and points at the Help and Guidance page,
 * which carries both the published FAQs and the HR Help Desk ticket flow — the real escalation path
 * that already exists. The navigation is built through {@link NavigationValidator} rather than
 * hardcoded, so even these canned responses cannot emit a page the caller cannot reach.
 */
@Component
@RequiredArgsConstructor
public class UnknownResponses {

    private final NavigationValidator navigationValidator;

    /** Retrieval found nothing authorised and relevant. The most common decline. */
    public AssistantResponse notEnoughKnowledge(AssistantRequestContext context) {
        return unknown(context,
                "I could not find enough information about that in OneHR to answer reliably, so I would "
                        + "rather not guess. Try rephrasing with the wording OneHR uses, or raise a ticket "
                        + "with HR from Help & Guidance.");
    }

    /** The question was not about OneHR. Declining is the product working, not failing. */
    public AssistantResponse outOfScope(AssistantRequestContext context) {
        return unknown(context,
                "I can only help with OneHR: its modules, pages, actions, workflows, roles and errors. "
                        + "I am not able to answer general questions outside the application.");
    }

    /** The model or embedding provider failed. Deliberately does not name the vendor or the error. */
    public AssistantResponse providerUnavailable(AssistantRequestContext context) {
        return unknown(context,
                "The assistant is temporarily unavailable. Please try again in a moment. If you need an "
                        + "answer now, Help & Guidance has the published FAQs and guides, and you can raise "
                        + "a ticket with HR from there.");
    }

    /**
     * The provider replied, but not with something that survived validation. Reads the same as an
     * outage on purpose: the distinction is ours to debug from the interaction log, not something
     * to make the user reason about.
     */
    public AssistantResponse malformedResponse(AssistantRequestContext context) {
        return unknown(context,
                "I was not able to produce a reliable answer to that. Please try rephrasing the question, "
                        + "or check Help & Guidance.");
    }

    /**
     * The user asked the assistant to <em>do</em> something rather than explain it.
     *
     * <p>Answers with the real workflow instead of refusing flatly — the useful reply to "approve
     * this leave request" is where the Approval Center is, not a bare no. Typed EXPLANATION rather
     * than UNKNOWN because this is a complete, correct answer: the assistant knows exactly what is
     * being asked and is telling the user how it actually gets done.
     */
    public AssistantResponse actionNotSupported(AssistantRequestContext context, String whereToGoPageId) {
        AssistantResponse.AssistantResponseBuilder builder = AssistantResponse.builder()
                .type(AssistantResponseType.EXPLANATION)
                .answer("I can explain how things work in OneHR, but I cannot make changes on your behalf "
                        + "yet — I am read-only for now, so nothing gets submitted, approved or edited by "
                        + "me. I can walk you through doing it yourself, or take you to the right page.")
                .steps(List.of())
                .related(List.of())
                .confidence(ConfidenceLevel.HIGH);

        navigationValidator.validate(whereToGoPageId, context).ifPresent(builder::navigation);
        return builder.build();
    }

    /** The per-user request budget is exhausted. */
    public AssistantResponse rateLimited(AssistantRequestContext context) {
        return unknown(context,
                "You have sent a lot of questions in a short time, so the assistant is pausing briefly. "
                        + "Please try again shortly.");
    }

    /** The question exceeded the accepted length. */
    public AssistantResponse messageTooLong(AssistantRequestContext context, int maxChars) {
        return unknown(context,
                "That question is longer than I can take in one go (limit " + maxChars + " characters). "
                        + "Try splitting it into smaller questions.");
    }

    private AssistantResponse unknown(AssistantRequestContext context, String answer) {
        AssistantResponse.AssistantResponseBuilder builder = AssistantResponse.builder()
                .type(AssistantResponseType.UNKNOWN)
                .answer(answer)
                .steps(List.of())
                .related(List.of())
                // Always LOW. An UNKNOWN that claimed high confidence would be telling the user we
                // are sure we do not know, which is not a useful thing to be sure about.
                .confidence(ConfidenceLevel.LOW);

        navigationValidator.validate("help", context).ifPresent(builder::navigation);
        return builder.build();
    }

    /** True when this response is one of the controlled declines rather than a real answer. */
    public static boolean isDecline(AssistantResponse response) {
        return response != null && response.getType() == AssistantResponseType.UNKNOWN;
    }

    /** Exposed for tests and for the assistant service to attach a validated help link. */
    public Optional<NavigationAction> helpNavigation(AssistantRequestContext context) {
        return navigationValidator.validate("help", context);
    }
}

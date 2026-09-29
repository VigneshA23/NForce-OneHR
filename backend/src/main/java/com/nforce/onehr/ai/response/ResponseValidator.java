package com.nforce.onehr.ai.response;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AssistantResponse;
import com.nforce.onehr.ai.contract.AssistantResponseType;
import com.nforce.onehr.ai.contract.ConfidenceLevel;
import com.nforce.onehr.ai.contract.NavigationAction;
import com.nforce.onehr.ai.contract.RelatedItem;
import com.nforce.onehr.ai.navigation.NavigationValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns raw model output into a response the frontend is allowed to receive.
 *
 * <p>The governing assumption is that model output is untrusted input, not a return value. Every
 * field is either recognised and rebuilt, or dropped. Nothing is passed through because it looked
 * plausible, and the method never throws or returns null — a caller must always get a usable
 * response, because the alternative is a 500 that {@code GlobalExceptionHandler} would render as a
 * bare "An unexpected error occurred".
 *
 * <p>Uses its own {@link ObjectMapper} rather than the application one. It needs
 * {@code READ_UNKNOWN_ENUM_VALUES_AS_NULL} so a single bad enum value degrades that field instead
 * of failing the whole parse and discarding an otherwise good answer, and that is not behaviour
 * worth imposing on the rest of OneHR.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResponseValidator {

    /** Long enough for a thorough how-to, short enough that a runaway generation cannot flood the UI. */
    private static final int MAX_ANSWER_CHARS = 4000;
    private static final int MAX_STEPS = 20;
    private static final int MAX_STEP_CHARS = 500;
    private static final int MAX_RELATED = 6;

    private final NavigationValidator navigationValidator;
    private final UnknownResponses unknownResponses;

    private final ObjectMapper mapper = new ObjectMapper()
            // A single unrecognised enum value degrades that one field instead of failing the whole
            // parse and throwing away an otherwise good answer.
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            // "how_to" and "High" are formatting slips, not different meanings.
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * @param rawModelOutput whatever the provider returned, verbatim
     * @return a valid response; a controlled decline if nothing usable could be salvaged
     */
    public AssistantResponse validate(String rawModelOutput, AssistantRequestContext context) {
        Optional<AssistantResponse> parsed = parse(rawModelOutput);
        if (parsed.isEmpty()) {
            return unknownResponses.malformedResponse(context);
        }
        AssistantResponse raw = parsed.get();

        String answer = cleanAnswer(raw.getAnswer());
        if (answer == null) {
            // Well-formed JSON with nothing in it is still nothing. Returning an empty bubble would
            // look like a UI bug rather than a failure to answer.
            log.info("Model returned a structurally valid response with no answer text");
            return unknownResponses.malformedResponse(context);
        }

        // An unrecognised type is a contract slip, not evidence the answer is wrong, and the answer
        // text gets shown either way. Falling back to UNKNOWN would label a perfectly good answer
        // "I do not know" and discard its steps; EXPLANATION claims nothing beyond "here is
        // information", carries no navigation unless one separately validates, and promises no
        // steps. So the honest, least-lossy default is EXPLANATION.
        AssistantResponseType type = raw.getType();
        if (type == null) {
            log.info("Model returned an unrecognised response type; treating it as EXPLANATION");
            type = AssistantResponseType.EXPLANATION;
        }
        Optional<NavigationAction> navigation = navigationValidator.validate(
                raw.getNavigation() == null ? null : raw.getNavigation().getPageId(), context);

        // A NAVIGATION answer whose destination did not survive validation is no longer a
        // navigation answer. Left as-is the user would get "here is where to go" with nothing to
        // click, which reads as a broken button rather than a refusal.
        if (type == AssistantResponseType.NAVIGATION && navigation.isEmpty()) {
            log.info("Downgrading NAVIGATION response to EXPLANATION: proposed page was not valid for this caller");
            type = AssistantResponseType.EXPLANATION;
        }

        // An UNKNOWN carrying confident steps is contradictory. Trust the type, drop the content -
        // the model saying it does not know is the part worth believing.
        List<String> steps = type == AssistantResponseType.UNKNOWN ? List.of() : cleanSteps(raw.getSteps());

        // A page id travels only in navigation, never in text the user reads. An answer that still
        // describes the prompt's internals is replaced whole rather than trimmed - what is left of
        // a leak after trimming is still a leak (ONEHR - "I was instructed to only reference pages
        // listed under REACHABLE PAGES and to use their exact pageId").
        Function<String, Optional<String>> labelFor = id -> navigationValidator.labelFor(id, context);
        answer = ConfidentialityGuard.hidePageIds(answer, labelFor);
        steps = steps.stream().map(step -> ConfidentialityGuard.hidePageIds(step, labelFor)).toList();
        List<RelatedItem> related = cleanRelated(raw.getRelated());
        List<String> shown = new ArrayList<>(steps);
        shown.add(answer);
        related.forEach(item -> shown.add(item.getLabel()));
        if (shown.stream().anyMatch(ConfidentialityGuard::leaksInternals)) {
            log.info("Replacing a response that leaked internal details");
            return unknownResponses.internalsNotDisclosed(context);
        }
        // "You are an HR Admin" to an Employee who only said so (ONEHR). The role is the account's.
        Optional<ConfidentialityGuard.Claim> attributed = shown.stream()
                .map(text -> ConfidentialityGuard.unfoundedAttribution(text, context.getAudiences()))
                .flatMap(Optional::stream)
                .findFirst();
        if (attributed.isPresent()) {
            log.info("Replacing a response that gave the user a role or access their account does not hold");
            return unknownResponses.claimNotHeld(context, attributed.get());
        }
        // Read-only: nothing was deleted, approved or changed, whatever the model says.
        if (shown.stream().anyMatch(ConfidentialityGuard::claimsAnAction)) {
            log.info("Replacing a response that claimed to have made a change");
            return unknownResponses.actionNotSupported(context, navigation.map(NavigationAction::getPageId).orElse(null));
        }

        return AssistantResponse.builder()
                .type(type)
                .answer(answer)
                .steps(steps)
                .navigation(navigation.orElse(null))
                .related(related)
                .confidence(resolveConfidence(raw.getConfidence(), type))
                .build();
    }

    /**
     * Extracts the response object from the provider's text.
     *
     * <p>Tolerant about packaging, strict about content. Models routinely wrap JSON in markdown
     * fences or add a sentence before it even when asked not to, and discarding an otherwise
     * correct answer over that would be needlessly brittle. What is not tolerated is anything that
     * changes meaning: unknown fields are dropped by the contract type, and unknown enum values
     * become null and get resolved explicitly below.
     */
    private Optional<AssistantResponse> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();

        String json = raw.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstNewline > 0 && closingFence > firstNewline) {
                json = json.substring(firstNewline + 1, closingFence).trim();
            }
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.info("Model output contained no JSON object");
            return Optional.empty();
        }
        json = json.substring(start, end + 1);

        try {
            return Optional.ofNullable(mapper.readValue(json, AssistantResponse.class));
        } catch (Exception e) {
            // Deliberately not logging the payload: it can contain the user's question verbatim,
            // and com.nforce.onehr runs at DEBUG in every environment.
            log.info("Model output was not parseable as an AssistantResponse: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String cleanAnswer(String answer) {
        if (answer == null || answer.isBlank()) return null;
        String trimmed = answer.trim();
        return trimmed.length() <= MAX_ANSWER_CHARS ? trimmed : trimmed.substring(0, MAX_ANSWER_CHARS).trim();
    }

    private List<String> cleanSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) return List.of();
        List<String> cleaned = new ArrayList<>();
        for (String step : steps) {
            if (step == null || step.isBlank()) continue;
            String trimmed = step.trim();
            cleaned.add(trimmed.length() <= MAX_STEP_CHARS ? trimmed : trimmed.substring(0, MAX_STEP_CHARS).trim());
            if (cleaned.size() == MAX_STEPS) break;
        }
        return List.copyOf(cleaned);
    }

    /**
     * Related items are advisory, so they are filtered rather than validated against the registry:
     * they render as plain text, carry no destination, and cannot move the user anywhere. A
     * related item that names something slightly off is a cosmetic flaw; one that could navigate
     * would not be, which is exactly why they do not.
     */
    private List<RelatedItem> cleanRelated(List<RelatedItem> related) {
        if (related == null || related.isEmpty()) return List.of();
        List<RelatedItem> cleaned = new ArrayList<>();
        for (RelatedItem item : related) {
            if (item == null || item.getLabel() == null || item.getLabel().isBlank()) continue;
            cleaned.add(RelatedItem.builder().label(item.getLabel().trim()).build());
            if (cleaned.size() == MAX_RELATED) break;
        }
        return List.copyOf(cleaned);
    }

    /**
     * Missing or unrecognised confidence becomes LOW, and UNKNOWN is always LOW.
     *
     * <p>Defaulting the other way would let a model that omitted the field appear more certain
     * than it earned, and confidence is exactly the signal a user leans on when deciding whether
     * to double-check an answer against HR.
     */
    private ConfidenceLevel resolveConfidence(ConfidenceLevel stated, AssistantResponseType type) {
        if (type == AssistantResponseType.UNKNOWN) return ConfidenceLevel.LOW;
        return stated == null ? ConfidenceLevel.LOW : stated;
    }
}

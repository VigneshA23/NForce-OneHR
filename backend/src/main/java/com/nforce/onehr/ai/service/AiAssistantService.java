package com.nforce.onehr.ai.service;

import com.nforce.onehr.ai.config.AiProperties;
import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AssistantResponse;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.KnowledgeRetriever;
import com.nforce.onehr.ai.contract.LlmCompletion;
import com.nforce.onehr.ai.contract.LlmProvider;
import com.nforce.onehr.ai.contract.LlmRequest;
import com.nforce.onehr.ai.contract.PageReference;
import com.nforce.onehr.ai.contract.RetrievalQuery;
import com.nforce.onehr.ai.contract.RetrievalResult;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.ai.data.AssistantDataService;
import com.nforce.onehr.ai.entity.AiConversation;
import com.nforce.onehr.ai.exception.AiProviderException;
import com.nforce.onehr.ai.exception.AiRateLimitExceededException;
import com.nforce.onehr.ai.navigation.NavigationValidator;
import com.nforce.onehr.ai.observability.AiInteractionLogger;
import com.nforce.onehr.ai.prompt.PromptBuilder;
import com.nforce.onehr.ai.response.ResponseValidator;
import com.nforce.onehr.ai.response.UnknownResponses;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.util.RoleUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates one assistant turn.
 *
 * <p>The flow is: resolve the caller from the authenticated principal, derive their roles and
 * audiences from the database, retrieve authorised knowledge, build the prompt, call the model,
 * validate the answer, validate the navigation, record the turn, return.
 *
 * <p><strong>Nothing about who the caller is comes from the request.</strong> The controller passes
 * only an email, taken from the JWT-authenticated principal, and everything else is looked up here
 * — the same pattern every other OneHR service already follows. A request body claiming a role is
 * simply ignored because there is nowhere for it to be read from.
 *
 * <p><strong>This service depends on no mutating domain service.</strong> Its only writes are
 * conversation rows and interaction-log rows. That is what makes the read-only guarantee structural
 * rather than a promise: there is no code path from here into leave, attendance or anything else
 * that changes data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiAssistantService {

    private final UserRepository userRepository;
    private final KnowledgeRetriever retriever;
    private final LlmProvider llmProvider;
    private final PromptBuilder promptBuilder;
    private final ResponseValidator responseValidator;
    private final NavigationValidator navigationValidator;
    private final UnknownResponses unknownResponses;
    private final ConversationService conversationService;
    private final AiRateLimiter rateLimiter;
    private final AssistantDataService dataService;
    private final AiInteractionLogger interactionLogger;
    private final AiProperties properties;

    /**
     * @param message       the user's question
     * @param conversationId prior conversation to continue, or null to start one
     * @param currentPageId  page the user is on; a retrieval hint only, re-validated here
     * @param actorEmail     from the authenticated principal, never from the request body
     */
    public AssistantResponse chat(String message, String conversationId, String currentPageId, String actorEmail) {
        long startedNanos = System.nanoTime();
        AssistantRequestContext context = buildContext(actorEmail);
        String question = message == null ? "" : message.trim();

        if (!properties.isEnabled()) {
            return refuse(unknownResponses.providerUnavailable(context), context, question,
                    AiInteractionLogger.DISABLED, startedNanos);
        }
        if (question.isEmpty()) {
            return refuse(unknownResponses.notEnoughKnowledge(context), context, question,
                    AiInteractionLogger.EMPTY_MESSAGE, startedNanos);
        }
        int maxChars = properties.getLimits().getMaxMessageChars();
        if (question.length() > maxChars) {
            return refuse(unknownResponses.messageTooLong(context, maxChars), context, question,
                    AiInteractionLogger.MESSAGE_TOO_LONG, startedNanos);
        }
        AiRateLimiter.RateLimitDecision decision = rateLimiter.tryAcquire(context.getUserId());
        if (!decision.allowed()) {
            // Unlike every other decline above, this one is not returned in-band as a 200/UNKNOWN
            // response — the rate-limiting brief specifically calls for a distinguishable HTTP 429
            // with retry information (see AiExceptionHandler). Still recorded the same way so
            // observability is unaffected by which path a turn was refused through.
            record(context, null, question, List.of(), null, null,
                    AiInteractionLogger.RATE_LIMITED, startedNanos);
            throw new AiRateLimitExceededException(decision.retryAfterSeconds());
        }

        // The hint is validated before it can influence anything. A spoofed or stale pageId is
        // dropped, so at worst it fails to bias ranking - it can never widen what is retrieved.
        Optional<PageReference> currentPage = navigationValidator.validateCurrentPage(currentPageId, context);
        AssistantRequestContext enriched = withCurrentPage(context, currentPage);

        AiConversation conversation = conversationService.resolve(conversationId, enriched.getUserId());

        AssistantResponse response = answer(question, enriched, currentPage, conversation, startedNanos);
        response.setConversationId(conversation.getId().toString());
        return response;
    }

    private AssistantResponse answer(String question,
                                     AssistantRequestContext context,
                                     Optional<PageReference> currentPage,
                                     AiConversation conversation,
                                     long startedNanos) {
        List<RetrievalResult> knowledge;
        try {
            knowledge = retriever.retrieve(RetrievalQuery.builder()
                    .rawQuery(question)
                    .audiences(context.getAudiences())
                    .moduleHint(currentPage.map(PageReference::getModule).orElse(null))
                    .pageIdHint(currentPage.map(PageReference::getPageId).orElse(null))
                    .build());
        } catch (AiProviderException e) {
            log.warn("Retrieval failed ({}): returning a controlled unavailable response", e.getMessage());
            AssistantResponse unavailable = unknownResponses.providerUnavailable(context);
            record(context, conversation.getId(), question, List.of(), null, unavailable,
                    AiInteractionLogger.RETRIEVAL_UNAVAILABLE, startedNanos);
            return unavailable;
        }

        if (knowledge.isEmpty()) {
            // Short-circuit before the model is called. Cheaper, and strictly safer: with no
            // grounding there is nothing for an answer to be based on except general knowledge,
            // which is exactly what this assistant must not do.
            log.debug("No authorised knowledge matched; returning UNKNOWN without calling the model");
            AssistantResponse unknown = unknownResponses.notEnoughKnowledge(context);
            conversationService.recordTurn(conversation.getId(), question,
                    unknown.getAnswer(), unknown.getType().name());
            record(context, conversation.getId(), question, List.of(), null, unknown,
                    AiInteractionLogger.NO_KNOWLEDGE, startedNanos);
            return unknown;
        }

        // The caller's own records, selected from what retrieval already matched. Fetched only
        // after retrieval has found something relevant, so an unrelated question never causes a
        // read of personal data - and never throws, so a failure here costs the live figure but
        // still leaves the static answer.
        AssistantDataService.LiveData liveData = dataService.fetch(context, knowledge);

        String systemPrompt = promptBuilder.buildSystemPrompt(context, knowledge, currentPage, liveData);
        String userPrompt = promptBuilder.buildUserPrompt(
                question, conversationService.recentTurns(conversation.getId()));

        LlmCompletion completion;
        try {
            completion = llmProvider.complete(LlmRequest.builder()
                    .systemPrompt(systemPrompt)
                    .userPrompt(userPrompt)
                    .jsonMode(true)
                    .build());
        } catch (AiProviderException e) {
            // Must not escape: GlobalExceptionHandler would turn it into a bare 500, and a provider
            // outage is not the user's error. Requirement 19 calls for a controlled response.
            log.warn("LLM provider failed ({}): returning a controlled unavailable response", e.getMessage());
            AssistantResponse unavailable = unknownResponses.providerUnavailable(context);
            record(context, conversation.getId(), question, knowledge, null, unavailable,
                    AiInteractionLogger.PROVIDER_UNAVAILABLE, startedNanos);
            return unavailable;
        }

        AssistantResponse response = responseValidator.validate(completion.getContent(), context);
        conversationService.recordTurn(conversation.getId(), question,
                response.getAnswer(), response.getType().name());
        record(context, conversation.getId(), question, knowledge, completion, response, null, startedNanos);
        return response;
    }

    /**
     * A turn refused before retrieval ever ran.
     *
     * <p>Still recorded, and with no conversation id, because these are the turns that reveal
     * operational problems rather than knowledge problems — a user repeatedly hitting the rate
     * limit, or a whole day of DISABLED because an environment variable was never set.
     */
    private AssistantResponse refuse(AssistantResponse response,
                                     AssistantRequestContext context,
                                     String question,
                                     String errorCode,
                                     long startedNanos) {
        record(context, null, question, List.of(), null, response, errorCode, startedNanos);
        return response;
    }

    /**
     * Note the third argument: the question goes in, but only to be measured.
     * {@link AiInteractionLogger.Turn} has no field that could hold it.
     */
    private void record(AssistantRequestContext context,
                        UUID conversationId,
                        String question,
                        List<RetrievalResult> knowledge,
                        LlmCompletion completion,
                        AssistantResponse response,
                        String errorCode,
                        long startedNanos) {
        interactionLogger.record(AiInteractionLogger.Turn.builder()
                .context(context)
                .conversationId(conversationId)
                .questionChars(question == null ? 0 : question.length())
                .knowledge(knowledge)
                .completion(completion)
                .response(response)
                .errorCode(errorCode)
                .latencyMs((System.nanoTime() - startedNanos) / 1_000_000L)
                .build());
    }

    /**
     * Builds the caller's context entirely from the database.
     *
     * <p>Note the two different role concepts, which are not interchangeable: audiences come from
     * every role the user actually holds and gate knowledge retrieval, while the shell role is the
     * single role their sidebar is keyed by and gates navigation. See {@link ShellRole}.
     */
    private AssistantRequestContext buildContext(String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new NoSuchElementException("Actor not found"));

        String primaryRoleCode = RoleUtils.primaryRoleCode(actor.getRoles(), "EMPLOYEE");
        Set<AudienceBucket> audiences = AudienceBucket.from(RoleUtils.audienceBuckets(actor.getRoles()));

        return AssistantRequestContext.builder()
                .userId(actor.getId())
                .actorEmail(actor.getEmail())
                .primaryRoleCode(primaryRoleCode)
                .shellRole(ShellRole.fromPrimaryRoleCode(primaryRoleCode))
                .audiences(audiences)
                .build();
    }

    private AssistantRequestContext withCurrentPage(AssistantRequestContext context, Optional<PageReference> page) {
        if (page.isEmpty()) return context;
        return AssistantRequestContext.builder()
                .userId(context.getUserId())
                .actorEmail(context.getActorEmail())
                .primaryRoleCode(context.getPrimaryRoleCode())
                .shellRole(context.getShellRole())
                .audiences(context.getAudiences())
                .currentPageId(page.get().getPageId())
                .currentModule(page.get().getModule())
                .build();
    }
}

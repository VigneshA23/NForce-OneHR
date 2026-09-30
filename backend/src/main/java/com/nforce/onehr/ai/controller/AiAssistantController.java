package com.nforce.onehr.ai.controller;

import com.nforce.onehr.ai.contract.AssistantResponse;
import com.nforce.onehr.ai.dto.AiBillingResponse;
import com.nforce.onehr.ai.dto.AiBillingSettingsResponse;
import com.nforce.onehr.ai.dto.AiRateLimitSettingsResponse;
import com.nforce.onehr.ai.dto.AiUsageStatsResponse;
import com.nforce.onehr.ai.dto.AssistantChatRequest;
import com.nforce.onehr.ai.dto.AssistantFeedbackRequest;
import com.nforce.onehr.ai.dto.AssistantHealthResponse;
import com.nforce.onehr.ai.dto.AssistantMessageDto;
import com.nforce.onehr.ai.dto.UpdateAiBillingSettingsRequest;
import com.nforce.onehr.ai.dto.UpdateAiRateLimitSettingsRequest;
import com.nforce.onehr.ai.config.AiProperties;
import com.nforce.onehr.ai.knowledge.KnowledgeIndexingService;
import com.nforce.onehr.ai.observability.AiInteractionLogger;
import com.nforce.onehr.ai.service.AiAssistantService;
import com.nforce.onehr.ai.service.AiBillingSettingsService;
import com.nforce.onehr.ai.service.AiRateLimitSettingsService;
import com.nforce.onehr.ai.service.AiUsageStatsService;
import com.nforce.onehr.ai.service.ConversationService;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The assistant API.
 *
 * <p>No SecurityConfig change was needed: {@code .anyRequest().authenticated()} already covers
 * these paths, so every endpoint here requires a valid JWT by default rather than by remembering to
 * add one.
 *
 * <p>Follows the house convention of injecting {@link Principal} and passing
 * {@code principal.getName()} - the authenticated email - down to the service, which re-resolves
 * the user. No endpoint accepts a role, permission or user id from the caller.
 *
 * <p>There is deliberately <strong>no endpoint that accepts an ActionRequest</strong>, not even a
 * stub returning 501. See {@code com.nforce.onehr.ai.action}.
 */
@RestController
@RequestMapping("/api/ai-assistant")
@RequiredArgsConstructor
@Slf4j
public class AiAssistantController {

    private final AiAssistantService assistantService;
    private final ConversationService conversationService;
    private final KnowledgeIndexingService indexingService;
    private final AiInteractionLogger interactionLogger;
    private final UserRepository userRepository;
    private final AiProperties properties;
    private final AiRateLimitSettingsService rateLimitSettingsService;
    private final AiUsageStatsService usageStatsService;
    private final AiBillingSettingsService billingSettingsService;

    /** Ask a question. Always returns 200 with a valid response, including for controlled declines. */
    @PostMapping("/chat")
    public AssistantResponse chat(@Valid @RequestBody AssistantChatRequest request, Principal principal) {
        return assistantService.chat(
                request.getMessage(),
                request.getConversationId(),
                request.getCurrentPageId(),
                principal.getName());
    }

    /** Replay a conversation. Owner-scoped: someone else's id returns an empty transcript, not a 403. */
    @GetMapping("/conversations/{id}")
    public List<AssistantMessageDto> conversation(@PathVariable UUID id, Principal principal) {
        return conversationService.transcript(id, currentUserId(principal)).stream()
                .map(message -> AssistantMessageDto.builder()
                        .sender(message.getSender())
                        .content(message.getContent())
                        .responseType(message.getResponseType())
                        .createdAt(message.getCreatedAt())
                        .build())
                .toList();
    }

    /** Start over. Empties the conversation but keeps its id, so the open tab stays coherent. */
    @PostMapping("/conversations/{id}/clear")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID id, Principal principal) {
        conversationService.clear(id, currentUserId(principal));
    }

    /**
     * Thumbs up or down on the last answer.
     *
     * <p>Attached to the most recent turn of the conversation, alongside the knowledge ids that
     * were retrieved for it — which is the whole value of collecting it, since a thumbs-down is
     * only actionable next to what the model was given to work with.
     *
     * <p>Always 204, even when nothing matched. A conversation that has been cleared, or an id
     * belonging to someone else, is not worth an error: the user pressed a thumb, and telling them
     * their rating failed helps nobody while telling an attacker whose conversation exists helps
     * them.
     */
    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@Valid @RequestBody AssistantFeedbackRequest request, Principal principal) {
        UUID conversationId;
        try {
            conversationId = UUID.fromString(request.getConversationId());
        } catch (IllegalArgumentException e) {
            return;
        }
        // The comment is never passed to the logger, only to the table: user free text, and
        // com.nforce.onehr runs at DEBUG in every environment.
        interactionLogger.recordFeedback(
                conversationId, currentUserId(principal), request.getRating(), request.getComment());
    }

    /**
     * Whether the assistant is usable.
     *
     * <p>Open to any authenticated user, not admin-gated, because the frontend needs it to decide
     * whether to render the launcher at all — gating it would mean every employee's first
     * interaction is a 403. It exposes only configuration and index state, never credentials.
     */
    @GetMapping("/health")
    public AssistantHealthResponse health() {
        KnowledgeIndexingService.IndexHealth health = indexingService.health();
        return AssistantHealthResponse.builder()
                .enabled(properties.isEnabled())
                .indexReady(health.isReady())
                .indexedChunks(health.getChunks())
                .lastIndexedAt(health.getLastIndexedAt())
                .knowledgeSources(health.getSources())
                .llmProvider(properties.getProvider())
                .embeddingProvider(health.getEmbeddingProvider())
                .embeddingDimensions(health.getEmbeddingDimensions())
                .build();
    }

    /**
     * Rebuild the knowledge index.
     *
     * <p>Super Admin only, and the tightest guard in this controller: it is the one operation that
     * writes, it costs real embedding calls, and a full rebuild briefly leaves the index
     * inconsistent. {@code hasRole('SUPER_ADMIN')} matches how {@code UserManagementController}
     * guards the other genuinely administrative surface.
     */
    @PostMapping("/admin/reindex")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public KnowledgeIndexingService.IndexingReport reindex(Principal principal) {
        log.info("Knowledge re-index requested by {}", principal.getName());
        return indexingService.reindex();
    }

    /**
     * The current per-user request budget. Super Admin only for both read and write — a deliberate
     * departure from {@code AttendanceRulesService.getRules()}'s open read, because the
     * rate-limiting brief's AC7 is explicit that this configuration is Super-Admin-only end to end.
     */
    @GetMapping("/admin/rate-limit-settings")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AiRateLimitSettingsResponse rateLimitSettings() {
        return rateLimitSettingsService.getForAdmin();
    }

    @PutMapping("/admin/rate-limit-settings")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AiRateLimitSettingsResponse updateRateLimitSettings(
            @Valid @RequestBody UpdateAiRateLimitSettingsRequest request, Principal principal) {
        return rateLimitSettingsService.update(request, currentUserId(principal));
    }

    /**
     * Backs the "API Usage" page under Insights — Super Admin only, same reasoning as the
     * rate-limit settings above: this is cost/operational visibility, not something every role
     * needs, and the rest of {@code ai_interaction_log} (audience buckets, retrieved knowledge ids)
     * is otherwise never exposed at all.
     */
    @GetMapping("/admin/usage-stats")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AiUsageStatsResponse usageStats(@RequestParam(required = false) Integer days) {
        return usageStatsService.stats(days);
    }

    /**
     * This calendar month's estimated spend against the configured budget — the progress bar on
     * the API Usage page. See {@code AiBillingSettings}/V198 for why this is an estimate computed
     * from OneHR's own token logs, not real Mistral billing.
     */
    @GetMapping("/admin/billing")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AiBillingResponse billing() {
        return billingSettingsService.currentMonthBilling();
    }

    @GetMapping("/admin/billing-settings")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AiBillingSettingsResponse billingSettings() {
        return billingSettingsService.getForAdmin();
    }

    @PutMapping("/admin/billing-settings")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AiBillingSettingsResponse updateBillingSettings(
            @Valid @RequestBody UpdateAiBillingSettingsRequest request, Principal principal) {
        return billingSettingsService.update(request, currentUserId(principal));
    }

    private UUID currentUserId(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .map(User::getId)
                .orElseThrow(() -> new NoSuchElementException("Actor not found"));
    }
}

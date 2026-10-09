package com.nforce.onehr.ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user request budget for the assistant.
 *
 * <p>Every chat message costs a real embedding call and a real completion call against a metered
 * API. Without a cap, one person holding down a key, or a loop in a client, spends money and
 * degrades the service for everyone.
 *
 * <p>In-memory and per-instance, following the same pragmatic choice already made for
 * {@code SseTicketService} and {@code ForceLogoutBroadcaster}, which also hold per-user state in a
 * {@code ConcurrentHashMap}. On a multi-instance deployment the effective limit is the configured
 * number multiplied by the instance count — acceptable for a cost guard, and not worth introducing
 * Redis for. It is a budget, not a security control.
 *
 * <p>The budget itself — enabled/requests-per-window/window length — is Super-Admin-configurable
 * and persisted, via {@link AiRateLimitSettingsService}. This class only owns the counting
 * mechanism; it re-reads the current budget (through that service's own short cache) on every
 * call, so a Super Admin's change takes effect without a restart.
 */
@Component
@RequiredArgsConstructor
public class AiRateLimiter {

    private final AiRateLimitSettingsService settingsService;

    private final Map<UUID, Deque<Instant>> recentRequests = new ConcurrentHashMap<>();

    /**
     * Records a request and reports whether it is within budget.
     */
    public RateLimitDecision tryAcquire(UUID userId) {
        AiRateLimitSettingsService.Snapshot settings = settingsService.currentForEnforcement();
        if (!settings.enabled() || settings.requestsPerWindow() <= 0) {
            return RateLimitDecision.allow();
        }

        Duration windowLength = Duration.ofMinutes(Math.max(1, settings.windowMinutes()));
        Instant now = Instant.now();
        Instant cutoff = now.minus(windowLength);
        Deque<Instant> window = recentRequests.computeIfAbsent(userId, key -> new ArrayDeque<>());

        // Per-user lock rather than a global one: two different users are never contending, and the
        // deque itself is not thread-safe.
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= settings.requestsPerWindow()) {
                // The caller may retry once the oldest counted request ages out of the window.
                long retryAfterSeconds = Duration.between(now, window.peekFirst().plus(windowLength)).getSeconds();
                return RateLimitDecision.reject(retryAfterSeconds);
            }
            window.addLast(now);
            return RateLimitDecision.allow();
        }
    }

    /**
     * Drops users whose window has fully expired.
     *
     * <p>Without this the map grows by one entry per user who ever used the assistant and never
     * shrinks. Called opportunistically rather than scheduled, since the entries are tiny and this
     * is housekeeping, not correctness. Uses the current window length only as an upper bound for
     * eviction; a shorter recorded window still empties correctly since the deque itself has
     * already dropped anything older than whatever window applied when it was last touched.
     */
    public void evictExpired() {
        Duration windowLength = Duration.ofMinutes(
                Math.max(1, settingsService.currentForEnforcement().windowMinutes()));
        Instant cutoff = Instant.now().minus(windowLength);
        recentRequests.entrySet().removeIf(entry -> {
            Deque<Instant> window = entry.getValue();
            synchronized (window) {
                while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                    window.pollFirst();
                }
                return window.isEmpty();
            }
        });
    }

    /** Whether the caller may proceed, and if not, a best-effort estimate of when they may retry. */
    public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
        // Named allow()/reject() rather than allowed()/rejected(), which would collide with the
        // record's own generated allowed() accessor (same name, same empty parameter list).
        static RateLimitDecision allow() {
            return new RateLimitDecision(true, 0);
        }

        static RateLimitDecision reject(long retryAfterSeconds) {
            return new RateLimitDecision(false, Math.max(1, retryAfterSeconds));
        }
    }
}

package com.nforce.onehr.ai.service;

import com.nforce.onehr.ai.config.AiProperties;
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
 * degrades the service for everyone. Neither planning document mentions this.
 *
 * <p>In-memory and per-instance, following the same pragmatic choice already made for
 * {@code SseTicketService} and {@code ForceLogoutBroadcaster}, which also hold per-user state in a
 * {@code ConcurrentHashMap}. On a multi-instance deployment the effective limit is the configured
 * number multiplied by the instance count — acceptable for a cost guard, and not worth introducing
 * Redis for. It is a budget, not a security control.
 */
@Component
@RequiredArgsConstructor
public class AiRateLimiter {

    private final AiProperties properties;

    private final Map<UUID, Deque<Instant>> recentRequests = new ConcurrentHashMap<>();

    /**
     * Records a request and reports whether it is within budget.
     *
     * @return true when the caller may proceed
     */
    public boolean tryAcquire(UUID userId) {
        int limit = properties.getLimits().getMaxRequestsPerUserPerHour();
        if (limit <= 0) return true;

        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        Deque<Instant> window = recentRequests.computeIfAbsent(userId, key -> new ArrayDeque<>());

        // Per-user lock rather than a global one: two different users are never contending, and the
        // deque itself is not thread-safe.
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= limit) return false;
            window.addLast(Instant.now());
            return true;
        }
    }

    /**
     * Drops users whose window has fully expired.
     *
     * <p>Without this the map grows by one entry per user who ever used the assistant and never
     * shrinks. Called opportunistically rather than scheduled, since the entries are tiny and this
     * is housekeeping, not correctness.
     */
    public void evictExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
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
}

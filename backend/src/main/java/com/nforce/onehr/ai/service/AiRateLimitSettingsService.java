package com.nforce.onehr.ai.service;

import com.nforce.onehr.ai.dto.AiRateLimitSettingsResponse;
import com.nforce.onehr.ai.dto.UpdateAiRateLimitSettingsRequest;
import com.nforce.onehr.ai.entity.AiRateLimitSettings;
import com.nforce.onehr.ai.repository.AiRateLimitSettingsRepository;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Super-Admin-editable AI Assistant rate-limit budget — singleton row, same shape as
 * {@link com.nforce.onehr.service.AttendanceRulesService}, with two deliberate differences from
 * that precedent:
 *
 * <p><b>Read is Super-Admin-only too.</b> {@code AttendanceRulesService.getRules()} is open to any
 * authenticated caller; this brief's AC7 is explicit that both read and write of the rate-limit
 * configuration are Super-Admin-only. Enforced at the controller (see
 * {@code AiAssistantController}'s {@code @PreAuthorize} on the GET) rather than on
 * {@link #getForAdmin()} itself, since a same-class call from within this service (there isn't
 * one, but a future one could be added) would bypass a method-level annotation here anyway —
 * Spring's proxy-based method security only intercepts calls that arrive through the bean's proxy.
 *
 * <p><b>Changes are audited.</b> {@code AttendanceRulesService} never calls {@link AuditService} at
 * all — a gap in that precedent, not a pattern to copy. This one does, per the brief's §14.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiRateLimitSettingsService {

    /** How stale the cached settings may be before {@link #currentForEnforcement()} re-reads the DB. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** Used only if the table has never been read successfully at all (should be unreachable once V192 has run). */
    private static final Snapshot SAFE_DEFAULT = new Snapshot(true, 60, 60);

    private final AiRateLimitSettingsRepository repository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    private final AtomicReference<CachedSnapshot> cache = new AtomicReference<>();

    /**
     * The values {@link AiRateLimiter} enforces against. Not an HTTP entry point and not
     * {@code @PreAuthorize}-guarded — every authenticated request that reaches the assistant needs
     * this, not just a Super Admin's own requests.
     *
     * <p>Reads through a {@link #CACHE_TTL} cache so a chat message never costs a primary-DB query
     * on its own (see §19 of the rate-limiting brief). Fails open: a DB read failure serves the
     * last-known-good cached value, or {@link #SAFE_DEFAULT} if nothing has ever been cached — a
     * rate-limiter outage must never be the reason the assistant stops working (§20).
     */
    public Snapshot currentForEnforcement() {
        CachedSnapshot cached = cache.get();
        if (cached != null && cached.isFresh()) {
            return cached.snapshot;
        }
        try {
            Snapshot loaded = toSnapshot(loadSingleton());
            cache.set(new CachedSnapshot(loaded, Instant.now()));
            return loaded;
        } catch (Exception e) {
            log.warn("Could not read AI rate limit settings; serving {}: {}",
                    cached != null ? "the last cached value" : "safe defaults", e.toString());
            return cached != null ? cached.snapshot : SAFE_DEFAULT;
        }
    }

    @Transactional(readOnly = true)
    public AiRateLimitSettingsResponse getForAdmin() {
        return AiRateLimitSettingsResponse.from(loadSingleton());
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public AiRateLimitSettingsResponse update(UpdateAiRateLimitSettingsRequest req, UUID actorId) {
        // Defense-in-depth alongside the request's own bean validation and V192's DB CHECKs — the
        // same belt-and-suspenders AttendanceRulesService.updateHalfDayMaxHours uses.
        if (req.getRequestsPerWindow() == null || req.getRequestsPerWindow() < 1 || req.getRequestsPerWindow() > 1000) {
            throw new IllegalArgumentException("requestsPerWindow must be between 1 and 1000");
        }
        if (req.getWindowMinutes() == null || req.getWindowMinutes() < 1 || req.getWindowMinutes() > 1440) {
            throw new IllegalArgumentException("windowMinutes must be between 1 and 1440 (24 hours)");
        }

        AiRateLimitSettings settings = loadSingleton();
        String before = auditSnapshot.toJson(Map.of(
                "enabled", settings.isEnabled(),
                "requestsPerWindow", settings.getRequestsPerWindow(),
                "windowMinutes", settings.getWindowMinutes()));

        settings.setEnabled(req.getEnabled());
        settings.setRequestsPerWindow(req.getRequestsPerWindow());
        settings.setWindowMinutes(req.getWindowMinutes());
        settings = repository.save(settings);

        String after = auditSnapshot.toJson(Map.of(
                "enabled", settings.isEnabled(),
                "requestsPerWindow", settings.getRequestsPerWindow(),
                "windowMinutes", settings.getWindowMinutes()));
        auditService.log(actorId, "AI_RATE_LIMIT_SETTINGS_UPDATED", settings.getId(), before, after);

        // Refreshed synchronously so the instance that made the change sees it on its very next
        // request. Other instances catch up within CACHE_TTL — see the plan's decision #3.
        cache.set(new CachedSnapshot(toSnapshot(settings), Instant.now()));

        return AiRateLimitSettingsResponse.from(settings);
    }

    /**
     * Marks whatever is currently cached as stale, without discarding it, so the next
     * {@link #currentForEnforcement()} call re-reads the database while a DB failure can still fall
     * back to the same snapshot. Test seam only — real staleness is just {@link #CACHE_TTL} elapsing.
     */
    void forceCacheStaleForTest() {
        CachedSnapshot current = cache.get();
        if (current != null) {
            cache.set(new CachedSnapshot(current.snapshot, Instant.now().minus(CACHE_TTL).minusSeconds(1)));
        }
    }

    private AiRateLimitSettings loadSingleton() {
        return repository.findBySingletonTrue()
                .orElseThrow(() -> new IllegalStateException(
                        "AI Rate Limit Settings row is missing — expected exactly one row seeded by migration V192"));
    }

    private static Snapshot toSnapshot(AiRateLimitSettings settings) {
        return new Snapshot(settings.isEnabled(), settings.getRequestsPerWindow(), settings.getWindowMinutes());
    }

    /** The three values {@link AiRateLimiter} needs, decoupled from the JPA entity. */
    public record Snapshot(boolean enabled, int requestsPerWindow, int windowMinutes) {
    }

    private record CachedSnapshot(Snapshot snapshot, Instant loadedAt) {
        boolean isFresh() {
            return Duration.between(loadedAt, Instant.now()).compareTo(CACHE_TTL) < 0;
        }
    }
}

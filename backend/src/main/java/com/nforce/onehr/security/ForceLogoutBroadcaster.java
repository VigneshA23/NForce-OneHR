package com.nforce.onehr.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory registry of open SSE connections per user, so UserManagementService can push a
 * FORCE_LOGOUT event the instant a Super Admin profile change commits, instead of the client
 * finding out on its next request/poll. token_version (see JwtAuthenticationFilter) is the
 * actual enforcement layer
 * — this only makes the client react fast; if the push never arrives (tab asleep, connection
 * dropped), the version check still guarantees the same outcome on that user's next API call.
 *
 * Single-instance only: a multi-instance deployment would need this backed by pub/sub (e.g.
 * Redis) instead of an in-process map, since a user's SSE connection may land on a different
 * instance than the one handling the role-change request.
 */
@Component
@Slf4j
public class ForceLogoutBroadcaster {

    private final Map<UUID, List<SseEmitter>> connections = new ConcurrentHashMap<>();

    /** Registers a new open connection for userId. No timeout — held open until we push+complete it or the client disconnects. */
    public SseEmitter register(UUID userId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = connections.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));

        return emitter;
    }

    /**
     * Tells every open connection of these users that their permissions changed (a role's grants or
     * active flag were edited) so the client re-fetches them right away. Unlike FORCE_LOGOUT the
     * connections stay open. Enforcement never depends on this — authorities are re-resolved on
     * every request — so a missed push only delays when the UI notices.
     */
    public void permissionsChanged(java.util.Collection<UUID> userIds) {
        for (UUID userId : userIds) {
            List<SseEmitter> emitters = connections.get(userId);
            if (emitters == null) continue;
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("PERMISSIONS_CHANGED").data("role-updated"));
                } catch (IOException | IllegalStateException e) {
                    log.debug("Failed to push PERMISSIONS_CHANGED to {}: {}", userId, e.getMessage());
                    emitters.remove(emitter);
                }
            }
        }
    }

    /** Pushes FORCE_LOGOUT to every open connection for userId (usually one per open tab/device), then closes them. */
    public void forceLogout(UUID userId) {
        List<SseEmitter> emitters = connections.remove(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("FORCE_LOGOUT").data("profile-updated"));
                emitter.complete();
            } catch (IOException e) {
                log.debug("Failed to push FORCE_LOGOUT to {}: {}", userId, e.getMessage());
                emitter.completeWithError(e);
            }
        }
    }
}

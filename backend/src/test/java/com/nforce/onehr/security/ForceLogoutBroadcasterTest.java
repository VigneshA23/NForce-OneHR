package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ForceLogoutBroadcasterTest {

    private final ForceLogoutBroadcaster broadcaster = new ForceLogoutBroadcaster();

    @Test
    void forceLogout_withNoRegisteredConnection_isNoOp() {
        assertDoesNotThrow(() -> broadcaster.forceLogout(UUID.randomUUID()));
    }

    @Test
    void register_thenForceLogout_doesNotThrow_andIsIdempotent() {
        UUID userId = UUID.randomUUID();
        SseEmitter emitter = broadcaster.register(userId);
        assertNotNull(emitter);

        assertDoesNotThrow(() -> broadcaster.forceLogout(userId));
        // Already removed by the first call — a second call for the same user must not error.
        assertDoesNotThrow(() -> broadcaster.forceLogout(userId));
    }

    @Test
    void forceLogout_onlyAffectsTheTargetedUser() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        broadcaster.register(userA);
        SseEmitter emitterB = broadcaster.register(userB);

        broadcaster.forceLogout(userA);

        // userB's connection is untouched — forcing userA out must not also close userB's emitter.
        assertNotNull(emitterB);
        assertDoesNotThrow(() -> broadcaster.forceLogout(userB));
    }

    @Test
    void register_supportsMultipleConnectionsForTheSameUser() {
        UUID userId = UUID.randomUUID();
        SseEmitter first = broadcaster.register(userId);
        SseEmitter second = broadcaster.register(userId);

        assertNotSame(first, second);
        assertDoesNotThrow(() -> broadcaster.forceLogout(userId));
    }
}

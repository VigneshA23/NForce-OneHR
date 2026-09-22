package com.nforce.onehr.ai.service;

import com.nforce.onehr.ai.dto.AiRateLimitSettingsResponse;
import com.nforce.onehr.ai.dto.UpdateAiRateLimitSettingsRequest;
import com.nforce.onehr.ai.entity.AiRateLimitSettings;
import com.nforce.onehr.ai.repository.AiRateLimitSettingsRepository;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiRateLimitSettingsServiceTest {

    @Mock private AiRateLimitSettingsRepository repository;
    @Mock private AuditService auditService;

    private AiRateLimitSettingsService service;
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // The real serializer, not a mock - it has no dependency worth stubbing and its own
        // behaviour (never throwing, degrading to null) is part of what these tests rely on.
        service = new AiRateLimitSettingsService(repository, auditService, new AuditSnapshotSerializer(
                new com.fasterxml.jackson.databind.ObjectMapper()));
        when(repository.save(any(AiRateLimitSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AiRateLimitSettings singletonRow(boolean enabled, int requestsPerWindow, int windowMinutes) {
        return AiRateLimitSettings.builder()
                .id(UUID.randomUUID())
                .enabled(enabled)
                .requestsPerWindow(requestsPerWindow)
                .windowMinutes(windowMinutes)
                .updatedAt(Instant.now())
                .build();
    }

    private UpdateAiRateLimitSettingsRequest request(boolean enabled, int requestsPerWindow, int windowMinutes) {
        UpdateAiRateLimitSettingsRequest req = new UpdateAiRateLimitSettingsRequest();
        req.setEnabled(enabled);
        req.setRequestsPerWindow(requestsPerWindow);
        req.setWindowMinutes(windowMinutes);
        return req;
    }

    @Test
    @DisplayName("a Super Admin can read the current configuration")
    void getForAdmin_returnsTheCurrentRow() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(true, 60, 60)));

        AiRateLimitSettingsResponse response = service.getForAdmin();

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getRequestsPerWindow()).isEqualTo(60);
        assertThat(response.getWindowMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("a missing singleton row fails loudly rather than guessing a value")
    void getForAdmin_missingRow_throws() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getForAdmin()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a Super Admin can update the configuration")
    void update_acceptsAValidChange() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(true, 60, 60)));

        AiRateLimitSettingsResponse response = service.update(request(true, 30, 10), actorId);

        assertThat(response.getRequestsPerWindow()).isEqualTo(30);
        assertThat(response.getWindowMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("an update takes effect on the next enforcement read without hitting the database again")
    void update_refreshesTheEnforcementCacheSynchronously() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(true, 60, 60)));
        service.update(request(true, 5, 15), actorId);

        AiRateLimitSettingsService.Snapshot snapshot = service.currentForEnforcement();

        assertThat(snapshot.requestsPerWindow()).isEqualTo(5);
        assertThat(snapshot.windowMinutes()).isEqualTo(15);
        // Exactly once: the update's own load, never a second read triggered by the enforcement path.
        verify(repository).findBySingletonTrue();
    }

    @Test
    @DisplayName("requestsPerWindow of zero is rejected")
    void update_rejectsZeroRequestsPerWindow() {
        assertThatThrownBy(() -> service.update(request(true, 0, 60), actorId))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("requestsPerWindow above 1000 is rejected")
    void update_rejectsRequestsPerWindowAboveCeiling() {
        assertThatThrownBy(() -> service.update(request(true, 1001, 60), actorId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("windowMinutes of zero is rejected")
    void update_rejectsZeroWindowMinutes() {
        assertThatThrownBy(() -> service.update(request(true, 30, 0), actorId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("windowMinutes above 1440 (24 hours) is rejected")
    void update_rejectsWindowMinutesAboveCeiling() {
        assertThatThrownBy(() -> service.update(request(true, 30, 1441), actorId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a successful update is audited with the actor, the action, and before/after values")
    void update_isAudited() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(true, 60, 60)));

        service.update(request(false, 10, 5), actorId);

        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(actorId), eq("AI_RATE_LIMIT_SETTINGS_UPDATED"), any(UUID.class),
                before.capture(), after.capture());

        assertThat(before.getValue()).contains("60");
        assertThat(after.getValue()).contains("10").contains("5").contains("false");
    }

    @Test
    @DisplayName("enforcement reads are cached rather than hitting the database on every call")
    void currentForEnforcement_isCachedAcrossCalls() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(true, 60, 60)));

        service.currentForEnforcement();
        service.currentForEnforcement();
        service.currentForEnforcement();

        verify(repository, org.mockito.Mockito.times(1)).findBySingletonTrue();
    }

    @Test
    @DisplayName("a database failure with a warm cache serves the last-known-good value, not an error")
    void currentForEnforcement_dbFailureAfterWarmCache_servesLastKnownGood() {
        when(repository.findBySingletonTrue())
                .thenReturn(Optional.of(singletonRow(true, 42, 20)))
                .thenThrow(new RuntimeException("connection reset"));

        AiRateLimitSettingsService.Snapshot first = service.currentForEnforcement();
        assertThat(first.requestsPerWindow()).isEqualTo(42);

        // Force the cache to be treated as stale so the next call re-reads (and this time fails).
        service.forceCacheStaleForTest();
        AiRateLimitSettingsService.Snapshot second = service.currentForEnforcement();

        assertThat(second.requestsPerWindow()).isEqualTo(42);
        assertThat(second.windowMinutes()).isEqualTo(20);
    }

    @Test
    @DisplayName("a database failure with nothing ever cached fails open with safe defaults")
    void currentForEnforcement_dbFailureBeforeAnyCache_failsOpen() {
        when(repository.findBySingletonTrue()).thenThrow(new RuntimeException("connection reset"));

        AiRateLimitSettingsService.Snapshot snapshot = service.currentForEnforcement();

        // A rate-limiter outage must never be why the assistant stops working - see the plan's
        // failure-behaviour decision. enabled=true with a real limit is "fail open to the same
        // shape everything else defaults to", not "fail open to unlimited".
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.requestsPerWindow()).isPositive();
        assertThat(snapshot.windowMinutes()).isPositive();
    }
}

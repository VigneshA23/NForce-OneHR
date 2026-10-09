package com.nforce.onehr.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The counting mechanism only — {@link AiRateLimitSettingsServiceTest} covers where the budget
 * itself comes from.
 */
@ExtendWith(MockitoExtension.class)
class AiRateLimiterTest {

    @Mock private AiRateLimitSettingsService settingsService;

    private AiRateLimiter limiter;
    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        limiter = new AiRateLimiter(settingsService);
    }

    private void budget(boolean enabled, int requestsPerWindow, int windowMinutes) {
        when(settingsService.currentForEnforcement())
                .thenReturn(new AiRateLimitSettingsService.Snapshot(enabled, requestsPerWindow, windowMinutes));
    }

    @Test
    @DisplayName("requests within the budget succeed")
    void withinBudgetSucceeds() {
        budget(true, 3, 60);

        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();
        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();
        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();
    }

    @Test
    @DisplayName("the request over budget is rejected with a positive retry estimate")
    void overBudgetIsRejected() {
        budget(true, 2, 60);

        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();
        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();

        AiRateLimiter.RateLimitDecision third = limiter.tryAcquire(userA);
        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("two users have fully independent budgets")
    void usersAreIsolated() {
        budget(true, 1, 60);

        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();
        assertThat(limiter.tryAcquire(userA).allowed()).isFalse();
        // User B's own single request still succeeds - A's usage never touches B's bucket.
        assertThat(limiter.tryAcquire(userB).allowed()).isTrue();
    }

    @Test
    @DisplayName("disabling the feature lets every request through, however small the configured limit")
    void disabledAlwaysAllows() {
        budget(false, 1, 60);

        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();
        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();
        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();
    }

    @Test
    @DisplayName("housekeeping never drops a still-live entry, so the budget still holds after it runs")
    void evictExpiredNeverDropsALiveEntry() {
        budget(true, 1, 60);
        assertThat(limiter.tryAcquire(userA).allowed()).isTrue();

        // A live (non-expired) entry must survive housekeeping - dropping it would let userA burst
        // straight past their budget on the very next request.
        limiter.evictExpired();

        assertThat(limiter.tryAcquire(userA).allowed()).isFalse();
    }

    @Test
    @DisplayName("concurrent requests from one user cannot exceed the limit")
    void concurrentRequestsCannotBypassTheLimit() throws InterruptedException {
        budget(true, 5, 60);
        int attempts = 50;
        // One thread per attempt: each task blocks on `go` after counting down `ready`, so a smaller
        // pool never starts the rest and `ready.await()` below waits forever.
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (limiter.tryAcquire(userA).allowed()) {
                    allowed.incrementAndGet();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(allowed.get()).isEqualTo(5);
    }
}

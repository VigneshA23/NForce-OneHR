package com.nforce.onehr.ai.exception;

import lombok.Getter;

/**
 * The caller has exhausted their assistant request budget for the current window.
 *
 * <p>Thrown rather than handled in-band (unlike every other assistant decline) because the
 * rate-limiting brief specifically calls for a distinguishable, conventional HTTP 429 with retry
 * information — see {@code AiExceptionHandler#handleRateLimitExceeded} and the "Exceeding the limit
 * becomes a real HTTP 429" decision in the assistant rate-limiting plan.
 */
@Getter
public class AiRateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public AiRateLimitExceededException(long retryAfterSeconds) {
        super("AI Assistant rate limit exceeded");
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }
}

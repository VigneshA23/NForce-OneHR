package com.nforce.onehr.ai.exception;

/**
 * Any failure talking to an external AI provider: timeout, transport error, non-2xx, rate limit,
 * or an unparseable payload.
 *
 * <p>Deliberately NOT one of the bare JDK exceptions this codebase normally throws
 * ({@code IllegalArgumentException}/{@code IllegalStateException}/{@code NoSuchElementException}),
 * because {@code GlobalExceptionHandler} maps those to 400/409/404 and everything else to a bare
 * 500. A provider outage is neither a client error nor an unexpected server bug — requirement §19
 * says it must surface as a controlled UNKNOWN answer. Having its own type is what lets
 * {@code AiAssistantService} catch exactly this and degrade gracefully.
 */
public class AiProviderException extends RuntimeException {

    private final String provider;
    private final boolean retryable;

    public AiProviderException(String provider, String message, boolean retryable) {
        super(message);
        this.provider = provider;
        this.retryable = retryable;
    }

    public AiProviderException(String provider, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.retryable = retryable;
    }

    public String getProvider() {
        return provider;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

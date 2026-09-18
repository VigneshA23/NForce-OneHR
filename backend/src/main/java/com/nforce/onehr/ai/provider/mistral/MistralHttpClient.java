package com.nforce.onehr.ai.provider.mistral;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.ai.config.AiProperties;
import com.nforce.onehr.ai.exception.AiProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Transport for the Mistral API: auth, timeouts, retries, and turning every failure into
 * {@link AiProviderException}.
 *
 * <p>Built on the JDK's {@code java.net.http.HttpClient}, the same choice {@code EmailService}
 * already makes for Resend. Adding WebClient would mean pulling WebFlux into a blocking MVC
 * application for one outbound call, which is a large dependency for no benefit here.
 *
 * <p>Unlike {@code EmailService}, which fires and forgets, this is synchronous: a chat completion
 * is the response, so there is nothing useful to do without it.
 *
 * <p><strong>Retries are narrow on purpose.</strong> Only 429 and 5xx are retried, because those
 * are the failures where the same request may succeed unchanged. A 400 or 401 will fail identically
 * every time, and retrying it would triple the latency the user waits before being told the
 * assistant is unavailable. There is no resilience library in this codebase, so this is hand-rolled
 * rather than pulling one in for a single call site.
 */
@Component
@Slf4j
public class MistralHttpClient {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MistralHttpClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getMistral().getConnectTimeoutSeconds()))
                .build();
    }

    /**
     * POSTs a JSON body and returns the parsed response.
     *
     * @param path API path, e.g. {@code /v1/chat/completions}
     * @throws AiProviderException on any transport error, non-2xx, or unparseable payload
     */
    public JsonNode postJson(String path, Object body) {
        AiProperties.Mistral cfg = properties.getMistral();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new AiProviderException("mistral", "No Mistral API key is configured", false);
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new AiProviderException("mistral", "Could not serialise request body", false, e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        AiProviderException last = null;
        for (int attempt = 1; attempt <= Math.max(1, cfg.getMaxAttempts()); attempt++) {
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    try {
                        return objectMapper.readTree(response.body());
                    } catch (Exception e) {
                        throw new AiProviderException("mistral", "Unparseable response body", false, e);
                    }
                }

                boolean retryable = status == 429 || status >= 500;
                // The response body can echo back the prompt, which contains the user's question,
                // so only the status is recorded. The interaction log is where per-request detail
                // belongs, not the application log that runs at DEBUG in every environment.
                last = new AiProviderException("mistral",
                        "Mistral API returned HTTP " + status, retryable);
                if (!retryable) throw last;
                log.warn("Mistral API HTTP {} (attempt {}/{}), retrying", status, attempt, cfg.getMaxAttempts());

            } catch (AiProviderException e) {
                if (!e.isRetryable()) throw e;
                last = e;
            } catch (java.io.InterruptedIOException e) {
                // Includes HttpTimeoutException. Retryable, but must not be swallowed silently.
                last = new AiProviderException("mistral", "Mistral API timed out", true, e);
                log.warn("Mistral API timeout (attempt {}/{})", attempt, cfg.getMaxAttempts());
            } catch (InterruptedException e) {
                // Restore the flag rather than eating it: a shutdown must not be mistaken for a
                // provider failure that is worth retrying.
                Thread.currentThread().interrupt();
                throw new AiProviderException("mistral", "Interrupted while calling Mistral", false, e);
            } catch (Exception e) {
                last = new AiProviderException("mistral", "Mistral API call failed: "
                        + e.getClass().getSimpleName(), true, e);
                log.warn("Mistral API transport failure (attempt {}/{}): {}",
                        attempt, cfg.getMaxAttempts(), e.getClass().getSimpleName());
            }

            if (attempt < cfg.getMaxAttempts()) {
                sleep(cfg.getRetryBackoffMillis() * attempt);
            }
        }
        throw last != null ? last
                : new AiProviderException("mistral", "Mistral API call failed", true);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("mistral", "Interrupted while backing off", false, e);
        }
    }
}

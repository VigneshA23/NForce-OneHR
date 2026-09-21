package com.nforce.onehr.ai.exception;

import com.nforce.onehr.ai.controller.AiAssistantController;
import com.nforce.onehr.dto.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Error handling scoped to the assistant controller only.
 *
 * <p>Scoped with {@code assignableTypes} rather than declared globally so it cannot change how any
 * existing OneHR endpoint behaves. {@code GlobalExceptionHandler} stays the handler for everything
 * else, exactly as before.
 *
 * <p>Its single job is to guarantee <strong>every response carries a JSON body</strong>. That is
 * not cosmetic: {@code authFetch.ts} treats any empty-bodied 403 on an authenticated {@code /api/}
 * call as a dead session, clears the auth store and hard-redirects to the login page. An assistant
 * permission error returning an empty 403 would therefore sign the user out of OneHR entirely —
 * losing whatever they were doing — because they asked the chatbot the wrong question.
 */
@RestControllerAdvice(assignableTypes = AiAssistantController.class)
@Slf4j
public class AiExceptionHandler {

    /**
     * A provider failure that reached the transport layer.
     *
     * <p>Normally unreachable: {@code AiAssistantService} catches these and degrades to a controlled
     * UNKNOWN with HTTP 200, because an outage is not the user's error. This exists so that if one
     * ever escapes by another route it still lands as a clean 503 rather than falling through to
     * {@code GlobalExceptionHandler}'s catch-all 500 and its generic message.
     */
    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ApiError> handleProviderFailure(AiProviderException e) {
        // The provider is not named to the client. Which vendor OneHR uses is an implementation
        // detail, and the exception message can carry upstream text.
        log.warn("AI provider failure surfaced to the API: provider={} retryable={}",
                e.getProvider(), e.isRetryable());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("The assistant is temporarily unavailable. Please try again shortly."));
    }

    /**
     * Something tried to execute an AI action.
     *
     * <p>Should be impossible — nothing constructs an ActionRequest and no endpoint accepts one —
     * so reaching this means a genuine bug worth a distinct status and a loud log rather than a
     * vague failure. 501 is honest: the capability is recognised and deliberately not implemented.
     */
    @ExceptionHandler(ActionExecutionDisabledException.class)
    public ResponseEntity<ApiError> handleDisabledAction(ActionExecutionDisabledException e) {
        log.error("AI action execution was attempted and blocked. This should be unreachable.", e);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(new ApiError("The assistant cannot perform actions."));
    }

    /**
     * The caller's assistant request budget is exhausted.
     *
     * <p>Unlike every other assistant decline (outage, no knowledge, message too long), which are
     * all returned in-band as HTTP 200 with a controlled {@code UNKNOWN} response, rate limiting is
     * deliberately surfaced as a real 429 — the rate-limiting brief's AC2/AC8/AC10 specifically call
     * for a distinguishable, conventional status with retry information, and a 429 with a JSON body
     * is safe against {@code authFetch.ts}'s empty-bodied-<strong>403</strong> session-kill trap
     * regardless (it only special-cases 403).
     *
     * <p>{@code Retry-After} is set per RFC 9110 alongside the body, and {@link ApiError#getLockedUntil()}
     * is reused for the same absolute-instant-to-count-down-to shape the frontend already renders
     * for account lockouts ({@code LoginLockedError}/{@code Login.tsx}) — no new response shape.
     */
    @ExceptionHandler(AiRateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimitExceeded(AiRateLimitExceededException e) {
        log.debug("Assistant rate limit exceeded: retryAfterSeconds={}", e.getRetryAfterSeconds());
        ApiError body = new ApiError(
                "You have reached the AI Assistant usage limit. Please try again shortly.",
                "AI_ASSISTANT_RATE_LIMIT_EXCEEDED",
                Instant.now().plusSeconds(e.getRetryAfterSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(body);
    }

    /**
     * A {@code @PreAuthorize} refusal, most realistically a non-Super-Admin calling
     * {@code /admin/reindex}.
     *
     * <p>Handled explicitly, even though {@code GlobalExceptionHandler} already maps this to a 403
     * with a body, because this advice would otherwise swallow it: the catch-all below matches
     * {@code AccessDeniedException} too, and which of two unordered {@code @RestControllerAdvice}
     * beans wins is not something to rely on. Without this method a permissions error could surface
     * as a 500 saying the assistant "ran into a problem" — wrong status, and misleading about the
     * one thing the user could actually act on.
     *
     * <p>The body is what makes it safe. {@code authFetch.ts} treats an empty-bodied 403 on an
     * authenticated {@code /api/} call as a dead session and hard-redirects to login, so a bodyless
     * response here would sign a perfectly valid user out of OneHR for calling an admin endpoint
     * they were never entitled to.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
        log.debug("Assistant authorization refused: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("You do not have permission to do that."));
    }

    /**
     * A bad request: a malformed path variable, an invalid body, or unparseable JSON.
     *
     * <p>Delegated to explicitly for the same reason as {@code AccessDeniedException} — the
     * catch-all below matches these too, and it would report a client's malformed input as a 500.
     * That is not cosmetic: a 500 tells whoever is watching the logs that OneHR is broken when in
     * fact somebody sent a bad conversation id, and it buries the real faults among the noise.
     *
     * <p>Wording follows {@code GlobalExceptionHandler} so a validation message reads the same on
     * these endpoints as on every other one in the application.
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiError> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(describe(e)));
    }

    private String describe(Exception e) {
        if (e instanceof MethodArgumentNotValidException invalid) {
            return invalid.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .collect(Collectors.joining("; "));
        }
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            String expected = mismatch.getRequiredType() != null
                    ? mismatch.getRequiredType().getSimpleName() : "the expected type";
            return "'" + mismatch.getName() + "' must be a valid " + expected;
        }
        // Unparseable JSON. The parser's message can quote the offending payload back, so it is
        // deliberately not passed through.
        return "The request body could not be read.";
    }

    /**
     * Anything else from the assistant.
     *
     * <p>Catch-all here as well as globally, so an unexpected assistant fault cannot produce the
     * one thing that must never happen on these paths: a response with no body.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unexpected assistant failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("The assistant ran into a problem. Please try again."));
    }
}

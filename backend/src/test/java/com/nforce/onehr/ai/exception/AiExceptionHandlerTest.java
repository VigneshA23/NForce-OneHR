package com.nforce.onehr.ai.exception;

import com.nforce.onehr.dto.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every assistant error path must carry a JSON body.
 *
 * <p>This is not a formatting preference. {@code installAuthFetch()} treats any empty-bodied 403 on
 * an authenticated {@code /api/} call as a dead session: it clears the auth store and hard-redirects
 * to the login page. A bodyless response from one of these endpoints would therefore sign a
 * perfectly valid user out of OneHR — losing whatever they were in the middle of — because they
 * asked the chatbot something they were not allowed to ask.
 */
class AiExceptionHandlerTest {

    private final AiExceptionHandler handler = new AiExceptionHandler();

    @Test
    @DisplayName("a permissions refusal is a 403 with a body, not a 500 and not an empty one")
    void accessDeniedIsForbiddenWithABody() {
        ResponseEntity<ApiError> response = handler.handleAccessDenied(
                new AccessDeniedException("Access is denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("a provider outage is a 503 with a body that does not name the vendor")
    void providerFailureIsServiceUnavailable() {
        ResponseEntity<ApiError> response = handler.handleProviderFailure(
                new AiProviderException("mistral", "quota exhausted: account xyz", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isNotBlank();
        // Which vendor OneHR uses is an implementation detail, and the upstream text can carry
        // account or quota information that has no business reaching a browser.
        assertThat(response.getBody().getMessage().toLowerCase())
                .doesNotContain("mistral")
                .doesNotContain("quota");
    }

    @Test
    @DisplayName("an attempted action is a 501 with a body")
    void disabledActionIsNotImplemented() {
        ResponseEntity<ApiError> response = handler.handleDisabledAction(
                new ActionExecutionDisabledException("leave.apply"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("an unexpected fault is a 500 with a body")
    void unexpectedFailureStillHasABody() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new RuntimeException("connection reset by peer at 10.0.0.4"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isNotBlank();
        assertThat(response.getBody().getMessage()).doesNotContain("10.0.0.4");
    }

    @Test
    @DisplayName("a malformed path variable is a 400, not a 500")
    void typeMismatchIsBadRequest() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "id", null, new IllegalArgumentException("bad uuid"));

        ResponseEntity<ApiError> response = handler.handleBadRequest(e);

        // Reporting a client's bad conversation id as a server fault tells whoever is watching the
        // logs that OneHR is broken, and buries the real faults in the noise.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("id").contains("UUID");
    }

    @Test
    @DisplayName("an invalid body is a 400 carrying the field messages")
    void validationFailureIsBadRequest() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "rating", "Rating is required"));
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(
                new MethodParameter(Probe.class.getDeclaredMethod("take", String.class), 0), binding);

        ResponseEntity<ApiError> response = handler.handleBadRequest(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        // Same wording as GlobalExceptionHandler, so a validation error reads identically here and
        // on every other OneHR endpoint.
        assertThat(response.getBody().getMessage()).isEqualTo("Rating is required");
    }

    @Test
    @DisplayName("unparseable JSON is a 400 that does not echo the payload back")
    void unreadableBodyIsBadRequest() {
        ResponseEntity<ApiError> response = handler.handleBadRequest(
                new HttpMessageNotReadableException(
                        "JSON parse error at {\"secret\":\"hunter2\"}", (HttpInputMessage) null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        // The parser quotes the offending payload, which on this endpoint is whatever the user
        // typed into the chat box.
        assertThat(response.getBody().getMessage()).doesNotContain("hunter2");
    }

    /** Only here to give MethodArgumentNotValidException a real MethodParameter to point at. */
    @SuppressWarnings("unused")
    private static final class Probe {
        void take(String value) { }
    }

    @Test
    @DisplayName("no handler here can return an empty body, whatever is added later")
    void everyHandlerReturnsABody() {
        List<Method> handlers = Arrays.stream(AiExceptionHandler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .toList();

        // Guards against a vacuous pass if the annotation or the class were ever restructured.
        assertThat(handlers).hasSizeGreaterThanOrEqualTo(4);

        for (Method method : handlers) {
            // A handler returning void, or ResponseEntity<Void>, would produce exactly the empty
            // 403 that logs the user out. Requiring ApiError in the signature makes that
            // impossible to add by accident rather than merely unlikely.
            assertThat(method.getGenericReturnType().getTypeName())
                    .as("%s must return a body", method.getName())
                    .isEqualTo("org.springframework.http.ResponseEntity<com.nforce.onehr.dto.ApiError>");
        }
    }

    @Test
    @DisplayName("the advice is scoped to the assistant controller only")
    void adviceCannotAffectExistingEndpoints() {
        RestControllerAdvice advice = AiExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

        assertThat(advice).isNotNull();
        // Scoped with assignableTypes rather than declared globally, so it cannot change how any
        // existing OneHR endpoint behaves. Dropping this would silently give every controller in
        // the application the assistant's catch-all.
        assertThat(advice.assignableTypes())
                .containsExactly(com.nforce.onehr.ai.controller.AiAssistantController.class);
    }
}

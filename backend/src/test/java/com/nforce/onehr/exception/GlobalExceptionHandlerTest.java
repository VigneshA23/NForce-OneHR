package com.nforce.onehr.exception;

import com.nforce.onehr.dto.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the bug report: a malformed filter id (e.g. an invalid UUID in
 * ?businessUnitId=) must surface as a client error, not fall through to the generic 500 "An
 * unexpected error occurred" handler — which is indistinguishable from a real server defect and
 * is exactly what made the reported failure look like a backend bug.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void malformedUuidQueryParam_returns400_notGeneric500() {
        MethodParameter parameter = mock(MethodParameter.class);
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "not-a-uuid", java.util.UUID.class, "businessUnitId", parameter,
                new IllegalArgumentException("Invalid UUID string: not-a-uuid"));

        ResponseEntity<ApiError> response = handler.handleTypeMismatch(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("businessUnitId"));
    }

    @Test
    void genuinelyUnhandledException_stillReturns500WithGenericMessage() {
        ResponseEntity<ApiError> response = handler.handleGeneral(new RuntimeException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("An unexpected error occurred", response.getBody().getMessage());
    }
}

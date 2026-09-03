package com.nforce.onehr.exception;

import com.nforce.onehr.dto.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiError> handleAccountLocked(AccountLockedException e) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(new ApiError(
                        "The account has been temporarily blocked for security reasons. Please try again after 4 hours.",
                        "ACCOUNT_LOCKED",
                        e.getLockedUntil()));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> handleAccountNotFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(e.getMessage(), "ACCOUNT_NOT_FOUND", null));
    }

    // The submitted (auto-suggested or manually-edited) Employee ID is already in use — a real
    // conflict (not a bug), so it gets its own status/code rather than falling through to the
    // generic DataIntegrityViolationException handler below.
    @ExceptionHandler(EmployeeCodeConflictException.class)
    public ResponseEntity<ApiError> handleEmployeeCodeConflict(EmployeeCodeConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(e.getMessage(), "EMPLOYEE_CODE_CONFLICT", null));
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(DisabledException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ApiError> handleUserNotFound(UsernameNotFoundException e) {
        // Intentionally generic — same response as bad credentials
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("Invalid credentials"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("Method not allowed"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("Access denied"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(e.getMessage()));
    }

    // Thrown by the multipart resolver itself (spring.servlet.multipart.max-file-size/
    // max-request-size) before a file ever reaches a controller method — without this handler
    // it falls through to the generic 500 below, which is indistinguishable from a real server
    // error and defeats the point of a clear "file too large" message.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("The uploaded file(s) exceed the maximum allowed size"));
    }

    // A query/path param that can't be converted to its declared type — e.g. a filter id that
    // isn't a well-formed UUID. Without this handler it falls through to the generic 500 below,
    // indistinguishable from a real server error, for what is actually a malformed request.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expectedType = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "the expected type";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("'" + e.getName() + "' must be a valid " + expectedType));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(message));
    }

    // Safety net for uniqueness/constraint violations that slip past an application-level
    // pre-check (e.g. a race between two concurrent requests, or a check that doesn't match
    // the DB's collation) — without this, it falls through to the generic 500 below and looks
    // like a server error instead of a simple "that value is already taken".
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("That value conflicts with an existing record. Please use a different value."));
    }

    // Section 6: a @Version-protected row (e.g. LeaveBalance) was concurrently modified by another
    // request between this transaction's read and write — the losing transaction lands here
    // instead of silently overwriting the winner's change. Safe to retry.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException e) {
        log.warn("Optimistic locking failure", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("This record was just updated by another request. Please refresh and try again."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("An unexpected error occurred"));
    }
}

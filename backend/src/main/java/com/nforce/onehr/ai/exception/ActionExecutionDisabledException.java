package com.nforce.onehr.ai.exception;

/**
 * Thrown by {@code DisabledActionExecutor} for every execution attempt, without exception.
 *
 * <p>Reaching this is not a user-facing error path in normal operation: the assistant recognises
 * "do this for me" requests while prompting and answers with the real workflow instead. If this is
 * ever actually thrown it means some code path tried to execute an action, which is a bug worth
 * surfacing loudly rather than swallowing.
 *
 * <p>Extends {@code IllegalStateException} so that, should it ever escape, this codebase's
 * {@code GlobalExceptionHandler} maps it to a 409 rather than leaking a stack trace as a bare 500.
 */
public class ActionExecutionDisabledException extends IllegalStateException {

    public ActionExecutionDisabledException(String actionId) {
        super("AI action execution is not enabled in this release (attempted action: "
                + (actionId == null ? "none" : actionId) + ")");
    }
}

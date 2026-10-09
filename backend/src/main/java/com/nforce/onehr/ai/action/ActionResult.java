package com.nforce.onehr.ai.action;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** Outcome of an attempted action. Only ever produced with DISABLED in this release. */
@Data
@Builder
public class ActionResult {

    private String actionId;

    private ActionOutcome outcome;

    /** User-facing explanation of what happened, or why it did not. */
    private String message;

    /** Outcome-specific detail (created id, validation failures). Empty today. */
    private Map<String, Object> details;

    public static ActionResult disabled(String actionId, String message) {
        return ActionResult.builder()
                .actionId(actionId)
                .outcome(ActionOutcome.DISABLED)
                .message(message)
                .details(Map.of())
                .build();
    }
}

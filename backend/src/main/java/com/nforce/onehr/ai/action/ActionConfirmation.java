package com.nforce.onehr.ai.action;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * A pending "are you sure?" for an action that declares
 * {@link ActionDefinition#requiresConfirmation()}.
 *
 * <p>The token is what makes confirmation meaningful: a future executor must require a token it
 * issued itself for this exact action and parameter set, so that neither the model nor a replayed
 * client call can manufacture consent the user never gave. Nothing issues tokens today.
 */
@Data
@Builder
public class ActionConfirmation {

    private String confirmationToken;

    private String actionId;

    /** Exactly what the user is being asked to approve, in plain language. */
    private String summary;

    /** The parameters the confirmation covers. A changed parameter must invalidate the token. */
    private Map<String, Object> parameters;

    private Instant expiresAt;
}

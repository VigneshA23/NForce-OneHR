package com.nforce.onehr.ai.action;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.exception.ActionExecutionDisabledException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The only {@link ActionExecutor} in this release. Refuses every request, unconditionally.
 *
 * <p><strong>There is deliberately no enable flag</strong> and no
 * {@code @ConditionalOnProperty}. A property switch would make "the assistant can mutate OneHR
 * data" reachable from an environment variable, which is precisely the failure mode worth
 * designing out: enabling actions must require shipping different code that has been reviewed and
 * tested, not setting a value in Railway.
 *
 * <p>It also ignores {@link ActionDefinition#enabled()}. That per-action flag is metadata for a
 * future executor; honouring it here would make a registry edit alone sufficient to start mutating
 * data.
 */
@Component
@Slf4j
public class DisabledActionExecutor implements ActionExecutor {

    @Override
    public ActionResult execute(ActionRequest request, AssistantRequestContext context) {
        String actionId = request == null ? null : request.getActionId();
        // Warn, not debug: nothing should be calling this. If it appears in logs, some code path
        // tried to execute an action and that is worth noticing.
        log.warn("Blocked AI action execution attempt for actionId={} - action execution is not enabled", actionId);
        throw new ActionExecutionDisabledException(actionId);
    }
}

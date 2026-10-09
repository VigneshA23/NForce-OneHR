package com.nforce.onehr.ai.action;

import com.nforce.onehr.ai.contract.AssistantRequestContext;

/**
 * The single seam through which an action could ever be performed.
 *
 * <p>{@link DisabledActionExecutor} is the only implementation in this release and refuses
 * everything. Keeping execution behind one narrow interface is what makes that refusal provable:
 * there is exactly one place to audit, rather than a mutation path spread across the assistant.
 */
public interface ActionExecutor {

    /**
     * @param request the structured action request
     * @param context server-derived caller context; implementations must never accept identity or
     *                authority from the request or from model output
     * @throws com.nforce.onehr.ai.exception.ActionExecutionDisabledException always, in this release
     */
    ActionResult execute(ActionRequest request, AssistantRequestContext context);
}

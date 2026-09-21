package com.nforce.onehr.ai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A validated "take me there" action attached to a response.
 *
 * <p>The LLM only ever supplies {@code pageId}. {@code label} is filled in server-side from the
 * page registry after {@code NavigationValidator} has confirmed the page exists and is reachable
 * by this caller's audience — so a label can never describe a page the user cannot open. No URL
 * is ever produced here; the frontend maps pageId to a route through nav.config.ts.
 *
 * <p>Needs a no-arg constructor as well as the builder because the same shape is deserialized
 * from raw LLM JSON before validation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NavigationAction {

    private String pageId;

    /** Server-filled display label, e.g. "Leave &amp; Holidays". Ignored if supplied by the model. */
    private String label;
}

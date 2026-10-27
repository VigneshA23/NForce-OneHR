package com.nforce.onehr.ai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A follow-up question the user may want to ask next. Purely informational.
 *
 * <p>Label only. It used to carry a {@code type} and a {@code refId} - the internal knowledge id -
 * which nothing on the frontend read, and which put implementation identifiers in every response
 * (ONEHR - chatbot exposed internal provider and action ids).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelatedItem {

    /** Human-readable label, asked verbatim when clicked. */
    private String label;
}

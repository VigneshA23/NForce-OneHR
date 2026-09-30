package com.nforce.onehr.ai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A pointer to further OneHR knowledge the user may want next. Purely informational. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelatedItem {

    /** The kind of thing referenced, as a {@link KnowledgeType} name. */
    private String type;

    /** The referenced knowledge/page/action id. */
    private String refId;

    /** Human-readable label. */
    private String label;
}

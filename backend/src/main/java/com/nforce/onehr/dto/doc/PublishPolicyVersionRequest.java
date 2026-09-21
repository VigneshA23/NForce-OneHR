package com.nforce.onehr.dto.doc;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Publish a substantive new version of an existing policy (PolicyService#publishNewVersion) —
 * distinct from {@link UpdatePolicyRequest}'s metadata-only edit. Any field left blank/null falls
 * back to the current version's value, so HR only has to supply what actually changed.
 */
@Data
public class PublishPolicyVersionRequest {

    @Size(max = 200)
    private String title;

    @Size(max = 20)
    private String version;

    private String description;

    @Size(max = 200)
    private String audience;

    private Boolean required;
}

package com.nforce.onehr.dto.doc;

import lombok.Value;

import java.time.Instant;
import java.util.UUID;

// Column-projection counterpart to Policy, deliberately excluding attachmentData (a BYTEA blob
// up to 10MB) — PolicyRepository's list queries select these fields only, so listing policies
// never pulls attachment bytes over the wire just to render a list.
@Value
public class PolicyListItem {
    Long id;
    String title;
    String version;
    String description;
    String audience;
    boolean required;
    Instant publishedAt;
    UUID publishedBy;
    boolean active;
    int versionNumber;
    Long previousVersionId;
    String attachmentName;
}

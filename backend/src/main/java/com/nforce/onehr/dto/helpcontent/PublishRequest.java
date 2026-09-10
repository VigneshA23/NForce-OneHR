package com.nforce.onehr.dto.helpcontent;

import lombok.Data;

import java.util.List;

/**
 * Chosen in the Review &amp; Publish flow, not the Add/Edit form — see
 * {@code HelpContentService#publish}. Validated there (non-empty, each code one of
 * EMPLOYEE/MANAGER/HR/ADMIN) rather than with bean-validation annotations, so the error message
 * matches this module's existing style (see the unsupported-type check in {@code create}).
 */
@Data
public class PublishRequest {

    private List<String> audience;
}

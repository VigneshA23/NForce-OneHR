package com.nforce.onehr.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One changed field of a {@code ProfileChangeRequest}; identifier-like values are masked unless the detail view asked for them. */
@Data @NoArgsConstructor @AllArgsConstructor
public class ProfileChangeFieldDto {
    private String field;
    private String label;
    private String oldValue;
    private String newValue;
}

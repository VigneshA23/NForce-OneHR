package com.nforce.onehr.dto.helpdesk;

import com.nforce.onehr.entity.HelpdeskCategory;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HelpdeskCategoryResponse {
    private Integer id;
    private String name;

    public static HelpdeskCategoryResponse from(HelpdeskCategory c) {
        return HelpdeskCategoryResponse.builder().id(c.getId()).name(c.getName()).build();
    }
}

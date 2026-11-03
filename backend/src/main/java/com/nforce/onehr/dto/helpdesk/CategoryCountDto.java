package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryCountDto {
    private String categoryName;
    private long ticketCount;
}

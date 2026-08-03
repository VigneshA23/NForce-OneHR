package com.nforce.onehr.dto.asset;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class AssetCategoryResponse {
    private Integer id;
    private String name;
}

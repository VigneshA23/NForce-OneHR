package com.nforce.onehr.dto.talent;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** The full set of level ids in their new display order — sort_order is assigned 1..N by list
 * position, so every active level must be included or the request is rejected. */
@Data
public class ReorderProficiencyLevelsRequest {

    @NotEmpty
    private List<UUID> orderedIds;
}

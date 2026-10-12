package com.nforce.onehr.dto.talent;

import lombok.Value;

@Value
public class MigrationScanSummary {
    int totalUnmigratedRows;
    int candidatesCreated;
    int candidatesUpdated;
    int autoMatched;
}

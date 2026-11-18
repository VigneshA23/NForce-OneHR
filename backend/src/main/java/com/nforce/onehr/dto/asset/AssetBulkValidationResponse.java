package com.nforce.onehr.dto.asset;

import java.util.List;

/** Result of parsing + validating a Bulk Add Assets .xlsx (nothing is written to the DB). */
public record AssetBulkValidationResponse(int totalRows, int validRows, int invalidRows, List<Row> rows) {

    /** One spreadsheet row; values are as parsed (normalized where valid). {@code rowNumber} is the Excel row number. */
    public record Row(int rowNumber, String assetTag, String category, String brand, String model,
                      String serialNumber, String purchaseDate, String purchaseCost, String warrantyExpiry,
                      String condition, String location, List<CellError> errors) {}

    public record CellError(String column, String message) {}
}

package com.nforce.onehr.dto.doc;

import java.util.List;

/** Result of parsing + validating a Bulk Add Policies .xlsx (nothing is written to the DB). */
public record PolicyBulkValidationResponse(int totalRows, int validRows, int invalidRows, List<Row> rows) {

    /**
     * One spreadsheet row; values are as parsed (normalized where valid). {@code rowNumber} is the Excel row
     * number. {@code required} is null when the cell couldn't be read as a boolean. {@code supersedesExisting}
     * is true when an active policy already has this exact title — publishing will deactivate it.
     */
    public record Row(int rowNumber, String title, String version, String description, String audience,
                      Boolean required, boolean supersedesExisting, List<CellError> errors) {}

    public record CellError(String column, String message) {}
}

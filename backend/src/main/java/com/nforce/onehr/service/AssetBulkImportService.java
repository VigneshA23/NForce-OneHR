package com.nforce.onehr.service;

import com.nforce.onehr.dto.asset.AssetBulkValidationResponse;
import com.nforce.onehr.dto.asset.AssetBulkValidationResponse.CellError;
import com.nforce.onehr.dto.asset.CreateAssetRequest;
import com.nforce.onehr.entity.AssetCategory;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.AssetCategoryRepository;
import com.nforce.onehr.repository.AssetRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.util.ExcelTemplateHeader;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bulk Add Assets: the .xlsx template, parsing + validation, and the import itself. Persistence goes
 * through {@link AssetService#bulkCreateAssets} so bulk rows follow the same rules as single create.
 */
@Service
@RequiredArgsConstructor
public class AssetBulkImportService {

    static final String SHEET = "Assets";
    static final String[] HEADERS = {"Asset Tag", "Category", "Brand", "Model", "Serial Number",
            "Purchase Date", "Purchase Cost", "Warranty Expiry", "Condition", "Location"};
    static final int PURCHASE_DATE_COL = 5;
    static final int WARRANTY_COL = 7;
    static final Set<String> MANDATORY = Set.of("Asset Tag", "Category");
    static final int MAX_ROWS = 500;
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final List<String> CONDITIONS = List.of("GOOD", "FAIR", "DAMAGED", "REPAIR");
    // assets.purchase_cost is NUMERIC(10,2)
    private static final BigDecimal MAX_COST = new BigDecimal("99999999.99");

    private final AssetService assetService;
    private final AssetRepository assetRepo;
    private final AssetCategoryRepository categoryRepo;
    private final LocationRepository locationRepo;

    // ── Template ──────────────────────────────────────────

    public byte[] template(String actorEmail) {
        assetService.requireCanCreate(actorEmail);
        return buildTemplate(categoryRepo.findAll().stream().map(AssetCategory::getName).toList(),
                locationRepo.findAll().stream().filter(Location::isActive).map(Location::getName).toList());
    }

    static byte[] buildTemplate(List<String> categoryNames, List<String> locationNames) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            // Text format keeps Excel from turning tags/serials into numbers; the two date columns are
            // real date cells instead (the parser reads either a date cell or YYYY-MM-DD text).
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));

            Sheet sheet = wb.createSheet(SHEET);
            ExcelTemplateHeader.write(wb, sheet, "Asset Details", HEADERS, MANDATORY);
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setDefaultColumnStyle(i, i == PURCHASE_DATE_COL || i == WARRANTY_COL ? date : text);
                sheet.setColumnWidth(i, Math.max(16, HEADERS[i].length() + 6) * 256); // room for the filter button
            }
            int first = ExcelTemplateHeader.FIRST_DATA_ROW, last = first + MAX_ROWS - 1;
            DataValidationHelper dv = sheet.getDataValidationHelper();
            sheet.addValidationData(dv.createValidation(
                    dv.createExplicitListConstraint(CONDITIONS.toArray(String[]::new)),
                    new CellRangeAddressList(first, last, 8, 8)));
            // ponytail: Excel has no native calendar picker in a plain .xlsx (needs macros/add-ins); a
            // date-typed, date-validated column is the most it allows. Excel for the web / Google Sheets
            // do show a calendar for such cells.
            for (int col : new int[]{PURCHASE_DATE_COL, WARRANTY_COL}) {
                DataValidation v = dv.createValidation(
                        dv.createDateConstraint(DataValidationConstraint.OperatorType.BETWEEN,
                                "DATE(1900,1,1)", "DATE(9999,12,31)", "yyyy-mm-dd"),
                        new CellRangeAddressList(first, last, col, col));
                v.setShowErrorBox(true);
                v.createErrorBox("Invalid date", "Enter a date, e.g. 2025-04-15.");
                v.setShowPromptBox(true);
                v.createPromptBox(HEADERS[col], "Enter a date as YYYY-MM-DD, e.g. 2025-04-15.");
                sheet.addValidationData(v);
            }

            Sheet help = wb.createSheet("Instructions");
            String[][] rows = {
                    {"Column", "Mandatory", "Expected value"},
                    {"Asset Tag", "Yes", "Unique tag, max 30 characters. Must not already exist in the inventory or repeat in this file."},
                    {"Category", "Yes", "Name of an existing asset category (see list below)."},
                    {"Brand", "No", "Max 80 characters."},
                    {"Model", "No", "Max 80 characters."},
                    {"Serial Number", "No", "Max 100 characters."},
                    {"Purchase Date", "No", "Date (the column is date-formatted), shown as YYYY-MM-DD, e.g. 2025-04-15."},
                    {"Purchase Cost", "No", "Non-negative number, up to 2 decimals, e.g. 85000.50."},
                    {"Warranty Expiry", "No", "Date, shown as YYYY-MM-DD. Cannot be before Purchase Date."},
                    {"Condition", "No", String.join(", ", CONDITIONS) + ". Defaults to GOOD when empty."},
                    {"Location", "No", "Name of an existing active location (see list below)."},
                    {},
                    {"Notes"},
                    {"Fill rows in the '" + SHEET + "' sheet, starting on row 3. Mandatory columns have red headers. Do not rename, reorder or remove the two header rows."},
                    {"Maximum " + MAX_ROWS + " assets per file. Empty rows are ignored."},
                    {"Status is set to AVAILABLE automatically; assign assets from the inventory after import."},
                    {"If any row has an error nothing is imported. Fix the file and upload it again."},
            };
            int r = 0;
            for (String[] values : rows) {
                Row row = help.createRow(r++);
                for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            }
            help.getRow(0).forEach(c -> c.setCellStyle(bold));
            help.getRow(12).getCell(0).setCellStyle(bold);
            r = writeList(help, r + 1, "Valid categories", categoryNames.stream(), bold);
            writeList(help, r + 1, "Valid locations", locationNames.stream(), bold);
            help.setColumnWidth(0, 24 * 256);
            help.setColumnWidth(1, 12 * 256);
            help.setColumnWidth(2, 90 * 256);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not generate the template", e);
        }
    }

    private static int writeList(Sheet sheet, int r, String title, Stream<String> names, CellStyle bold) {
        Cell t = sheet.createRow(r++).createCell(0);
        t.setCellValue(title);
        t.setCellStyle(bold);
        for (String n : names.sorted(String.CASE_INSENSITIVE_ORDER).toList()) sheet.createRow(r++).createCell(0).setCellValue(n);
        return r;
    }

    // ── Validate / import ─────────────────────────────────

    public AssetBulkValidationResponse validate(MultipartFile file, String actorEmail) {
        assetService.requireCanCreate(actorEmail);
        return parse(file).response();
    }

    /** Re-parses and re-validates the file server-side; imports all rows or none. */
    public Map<String, Object> importAssets(MultipartFile file, String actorEmail) {
        assetService.requireCanCreate(actorEmail);
        Parsed parsed = parse(file);
        int invalid = parsed.response().invalidRows();
        if (invalid > 0) {
            throw new IllegalArgumentException(invalid + " row(s) have errors. Fix the file and upload it again — nothing was imported.");
        }
        int imported = assetService.bulkCreateAssets(parsed.requests(), actorEmail);
        return Map.of("imported", imported, "message", imported + " asset(s) imported successfully");
    }

    record Parsed(AssetBulkValidationResponse response, List<CreateAssetRequest> requests) {}

    Parsed parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("The uploaded file is empty");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) throw new IllegalArgumentException("Only .xlsx files are supported");
        if (file.getSize() > MAX_FILE_BYTES) throw new IllegalArgumentException("The file is larger than 5 MB");

        Workbook wb;
        try (InputStream in = file.getInputStream()) {
            wb = new XSSFWorkbook(in);
        } catch (IOException | RuntimeException e) {
            // Not a real xlsx (renamed file, corrupt zip, zip bomb guard, ...) — never echo POI internals.
            throw new IllegalArgumentException("Could not read the file. Upload a valid .xlsx created from the template.");
        }
        try {
            return parse(wb);
        } finally {
            try { wb.close(); } catch (IOException ignored) { /* read-only, nothing to flush */ }
        }
    }

    private Parsed parse(Workbook wb) {
        Sheet sheet = Optional.ofNullable(wb.getSheet(SHEET)).orElseGet(() -> wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null);
        if (sheet == null) throw new IllegalArgumentException("The file has no sheets");
        DataFormatter fmt = new DataFormatter();
        FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();

        // Current template: title band on row 1, headers on row 2. Older downloads had headers on row 1.
        int headerRow;
        if (headersMatch(sheet.getRow(ExcelTemplateHeader.HEADER_ROW), fmt, eval)) headerRow = ExcelTemplateHeader.HEADER_ROW;
        else if (headersMatch(sheet.getRow(0), fmt, eval)) headerRow = 0;
        else throw new IllegalArgumentException("Column headers don't match the template (expected: "
                + String.join(", ", HEADERS) + "). Download the latest template.");

        Map<String, AssetCategory> categories = byLowerName(categoryRepo.findAll(), AssetCategory::getName);
        Map<String, Location> locations = byLowerName(locationRepo.findAll(), Location::getName);
        Set<String> existingTags = new HashSet<>(assetRepo.findAllAssetTagsLowercase());
        Map<String, Integer> tagRowInFile = new HashMap<>();

        List<AssetBulkValidationResponse.Row> rows = new ArrayList<>();
        List<CreateAssetRequest> requests = new ArrayList<>();
        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (isBlank(row, fmt, eval)) continue;
            if (rows.size() == MAX_ROWS) {
                throw new IllegalArgumentException("The file has more than " + MAX_ROWS + " asset rows. Split it into smaller files.");
            }
            int rowNumber = r + 1;
            List<CellError> errors = new ArrayList<>();
            CreateAssetRequest req = new CreateAssetRequest();

            String tag = text(row.getCell(0), fmt, eval);
            if (tag.isEmpty()) errors.add(new CellError("Asset Tag", "Asset Tag is required"));
            else if (tag.length() > 30) errors.add(new CellError("Asset Tag", "Must be at most 30 characters"));
            else {
                String key = tag.toLowerCase(Locale.ROOT);
                Integer firstRow = tagRowInFile.putIfAbsent(key, rowNumber);
                if (firstRow != null) errors.add(new CellError("Asset Tag", "Duplicate of row " + firstRow + " in this file"));
                else if (existingTags.contains(key)) errors.add(new CellError("Asset Tag", "Asset tag already exists in inventory"));
            }
            req.setAssetTag(tag);

            String categoryName = text(row.getCell(1), fmt, eval);
            AssetCategory category = categories.get(categoryName.toLowerCase(Locale.ROOT));
            if (categoryName.isEmpty()) errors.add(new CellError("Category", "Category is required"));
            else if (category == null) errors.add(new CellError("Category", "No asset category named '" + categoryName + "'"));
            else { categoryName = category.getName(); req.setCategoryId(category.getId()); }

            req.setBrand(optional(text(row.getCell(2), fmt, eval), "Brand", 80, errors));
            req.setModel(optional(text(row.getCell(3), fmt, eval), "Model", 80, errors));
            req.setSerialNumber(optional(text(row.getCell(4), fmt, eval), "Serial Number", 100, errors));

            String[] purchaseDate = {null};
            req.setPurchaseDate(date(row.getCell(5), "Purchase Date", fmt, eval, errors, purchaseDate));
            String[] warranty = {null};
            req.setWarrantyExpiry(date(row.getCell(7), "Warranty Expiry", fmt, eval, errors, warranty));
            if (req.getPurchaseDate() != null && req.getWarrantyExpiry() != null && req.getWarrantyExpiry().isBefore(req.getPurchaseDate())) {
                errors.add(new CellError("Warranty Expiry", "Cannot be before Purchase Date"));
            }

            String costText = null;
            Cell costCell = row.getCell(6);
            if (cellType(costCell) == CellType.NUMERIC) {
                req.setPurchaseCost(BigDecimal.valueOf(costCell.getNumericCellValue()));
                costText = req.getPurchaseCost().toPlainString();
            } else {
                costText = text(costCell, fmt, eval).replace(",", "");
                if (!costText.isEmpty()) {
                    try { req.setPurchaseCost(new BigDecimal(costText)); }
                    catch (NumberFormatException e) { errors.add(new CellError("Purchase Cost", "Must be a number")); }
                }
            }
            BigDecimal cost = req.getPurchaseCost();
            if (cost != null) {
                if (cost.signum() < 0) errors.add(new CellError("Purchase Cost", "Cannot be negative"));
                else if (cost.compareTo(MAX_COST) > 0) errors.add(new CellError("Purchase Cost", "Must be at most 99,999,999.99"));
                else if (cost.stripTrailingZeros().scale() > 2) errors.add(new CellError("Purchase Cost", "At most 2 decimal places"));
            }

            String condition = text(row.getCell(8), fmt, eval).toUpperCase(Locale.ROOT);
            if (condition.isEmpty()) condition = "GOOD";
            if (!CONDITIONS.contains(condition)) errors.add(new CellError("Condition", "Must be one of " + String.join(", ", CONDITIONS)));
            req.setCondition(condition);

            String locationName = text(row.getCell(9), fmt, eval);
            if (!locationName.isEmpty()) {
                Location location = locations.get(locationName.toLowerCase(Locale.ROOT));
                if (location == null) errors.add(new CellError("Location", "No location named '" + locationName + "'"));
                else if (!location.isActive()) errors.add(new CellError("Location", "Location '" + location.getName() + "' is inactive"));
                else { locationName = location.getName(); req.setLocationId(location.getId()); }
            }

            rows.add(new AssetBulkValidationResponse.Row(rowNumber, tag, categoryName, req.getBrand(), req.getModel(),
                    req.getSerialNumber(), purchaseDate[0], costText == null || costText.isEmpty() ? null : costText,
                    warranty[0], condition, locationName.isEmpty() ? null : locationName, errors));
            if (errors.isEmpty()) requests.add(req);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("No asset rows found in the '" + SHEET + "' sheet");

        int invalid = rows.size() - requests.size();
        return new Parsed(new AssetBulkValidationResponse(rows.size(), requests.size(), invalid, rows), requests);
    }

    // ── Cell helpers ──────────────────────────────────────

    private static CellType cellType(Cell c) {
        if (c == null) return CellType.BLANK;
        return c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
    }

    private static String text(Cell c, DataFormatter fmt, FormulaEvaluator eval) {
        if (c == null) return "";
        try {
            return fmt.formatCellValue(c, eval).trim();
        } catch (RuntimeException e) {
            // Unsupported/broken formula: fall back to the raw formula text, which then fails validation.
            return fmt.formatCellValue(c).trim();
        }
    }

    private static boolean headersMatch(Row header, DataFormatter fmt, FormulaEvaluator eval) {
        if (header == null) return false;
        for (int i = 0; i < HEADERS.length; i++) {
            if (!HEADERS[i].equalsIgnoreCase(text(header.getCell(i), fmt, eval).replace("*", "").trim())) return false;
        }
        return true;
    }

    private static boolean isBlank(Row row, DataFormatter fmt, FormulaEvaluator eval) {
        if (row == null) return true;
        for (int i = 0; i < HEADERS.length; i++) if (!text(row.getCell(i), fmt, eval).isEmpty()) return false;
        return true;
    }

    private static String optional(String value, String column, int max, List<CellError> errors) {
        if (value.isEmpty()) return null;
        if (value.length() > max) errors.add(new CellError(column, "Must be at most " + max + " characters"));
        return value;
    }

    /** Accepts a real Excel date cell or YYYY-MM-DD text; {@code display[0]} receives the preview value. */
    private static LocalDate date(Cell c, String column, DataFormatter fmt, FormulaEvaluator eval,
                                  List<CellError> errors, String[] display) {
        if (cellType(c) == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
            LocalDate d = c.getLocalDateTimeCellValue().toLocalDate();
            display[0] = d.toString();
            return d;
        }
        String s = text(c, fmt, eval);
        if (s.isEmpty()) return null;
        display[0] = s;
        try {
            return LocalDate.parse(s); // ISO_LOCAL_DATE, strict: rejects 2025-02-30, 15/04/2025, ...
        } catch (DateTimeParseException e) {
            errors.add(new CellError(column, "Must be a valid date in YYYY-MM-DD format"));
            return null;
        }
    }

    private static <T> Map<String, T> byLowerName(List<T> items, Function<T, String> name) {
        return items.stream().collect(Collectors.toMap(i -> name.apply(i).trim().toLowerCase(Locale.ROOT), i -> i, (a, b) -> a));
    }
}

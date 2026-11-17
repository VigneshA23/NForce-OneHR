package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.PolicyBulkValidationResponse;
import com.nforce.onehr.dto.doc.PolicyBulkValidationResponse.CellError;
import com.nforce.onehr.dto.doc.PolicyListItem;
import com.nforce.onehr.dto.doc.PublishPolicyRequest;
import com.nforce.onehr.repository.PolicyRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bulk Add Policies: the .xlsx template, parsing + validation, and the import itself. Publishing goes
 * through {@link PolicyService#bulkPublish}, i.e. the existing {@link PolicyService#publish} per row, so
 * bulk rows get the same supersede, acknowledgment seeding and notifications as a single publish.
 */
@Service
@RequiredArgsConstructor
public class PolicyBulkImportService {

    static final String SHEET = "Policies";
    static final String TITLE = "Policy Title";
    static final String VERSION = "Version";
    static final String DESCRIPTION = "Description";
    static final String AUDIENCE = "Audience";
    static final String ACK = "Acknowledgment Required";
    static final String[] HEADERS = {TITLE, VERSION, DESCRIPTION, AUDIENCE, ACK};
    static final Set<String> REQUIRED_HEADERS = Set.of(TITLE, VERSION, DESCRIPTION);
    static final String ALL_EMPLOYEES = "All Employees";
    // ponytail: each row is a full publish() (ack + notification row per targeted employee) in ONE
    // transaction, so the cap bounds that transaction's size; raise it if HR needs bigger batches.
    static final int MAX_ROWS = 100;
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    // Display order for the template/instructions; validity is PolicyService.ALL_ROLE_CODES.
    static final List<String> ROLE_CODES = List.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN");

    private final PolicyService policyService;
    private final PolicyRepository policyRepo;

    // ── Template ──────────────────────────────────────────

    public byte[] template(String actorEmail) {
        policyService.requireCanCreate(actorEmail);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            // Text format keeps Excel from turning versions like "1.0" into the number 1.
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            text.setWrapText(true);

            Sheet sheet = wb.createSheet(SHEET);
            Row header = sheet.createRow(0);
            int[] widths = {36, 12, 70, 34, 26};
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(bold);
                sheet.setDefaultColumnStyle(i, text);
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 1);
            DataValidationHelper dv = sheet.getDataValidationHelper();
            sheet.addValidationData(dv.createValidation(
                    dv.createExplicitListConstraint(new String[]{"TRUE", "FALSE"}),
                    new CellRangeAddressList(1, MAX_ROWS, 4, 4)));
            // Suggestions only: a comma-separated combination of codes is typed by hand, so no error box.
            List<String> audienceChoices = new ArrayList<>(List.of(ALL_EMPLOYEES));
            audienceChoices.addAll(ROLE_CODES);
            DataValidation audience = dv.createValidation(
                    dv.createExplicitListConstraint(audienceChoices.toArray(String[]::new)),
                    new CellRangeAddressList(1, MAX_ROWS, 3, 3));
            audience.setShowErrorBox(false);
            sheet.addValidationData(audience);

            Sheet help = wb.createSheet("Instructions");
            String codes = String.join(", ", ROLE_CODES);
            String[][] rows = {
                    {"Column", "Mandatory", "Expected value"},
                    {TITLE, "Yes", "Policy name, max 200 characters. Must not repeat (ignoring case) within this file."},
                    {VERSION, "Yes", "Version label, max 20 characters, e.g. 1.0."},
                    {DESCRIPTION, "Yes", "Full policy text."},
                    {AUDIENCE, "No", "Empty or 'All Employees' = everyone. Otherwise one or more of " + codes
                            + ", separated by commas (e.g. MANAGER, HR_ADMIN)."},
                    {ACK, "No", "TRUE or FALSE (YES/NO and 1/0 also accepted). Defaults to TRUE when empty."},
                    {},
                    {"Notes"},
                    {"Fill rows in the '" + SHEET + "' sheet. Do not rename or remove the header row."},
                    {"Maximum " + MAX_ROWS + " policies per file. Empty rows are ignored."},
                    {"If an active policy already has the exact same title, publishing replaces it (the old one is deactivated) — same as publishing it individually."},
                    {"Every policy is sent to its audience: acknowledgment records and notifications are created for each employee in it."},
                    {"Attachments are not part of the bulk upload — add them to individual policies afterwards."},
                    {"If any row has an error nothing is published. Fix the file and upload it again."},
                    {},
                    {"Example row (copy into the '" + SHEET + "' sheet)"},
                    HEADERS,
                    {"Leave Policy", "1.0", "Employees are entitled to 24 days of paid leave per calendar year...", ALL_EMPLOYEES, "TRUE"},
            };
            int r = 0;
            for (String[] values : rows) {
                Row row = help.createRow(r++);
                for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            }
            for (int boldRow : new int[]{0, 7, 15, 16}) help.getRow(boldRow).forEach(c -> c.setCellStyle(bold));
            help.setColumnWidth(0, 30 * 256);
            help.setColumnWidth(1, 12 * 256);
            help.setColumnWidth(2, 100 * 256);
            help.setColumnWidth(3, 18 * 256);
            help.setColumnWidth(4, 24 * 256);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not generate the template", e);
        }
    }

    // ── Validate / import ─────────────────────────────────

    public PolicyBulkValidationResponse validate(MultipartFile file, String actorEmail) {
        policyService.requireCanCreate(actorEmail);
        return parse(file).response();
    }

    /** Re-parses and re-validates the file server-side; publishes all rows or none. */
    public Map<String, Object> importPolicies(MultipartFile file, String actorEmail) {
        policyService.requireCanCreate(actorEmail);
        Parsed parsed = parse(file);
        int invalid = parsed.response().invalidRows();
        if (invalid > 0) {
            throw new IllegalArgumentException(invalid + " row(s) have errors. Fix the file and upload it again — nothing was published.");
        }
        int published = policyService.bulkPublish(actorEmail, parsed.requests());
        return Map.of("published", published, "message", published + " polic" + (published == 1 ? "y" : "ies") + " published successfully");
    }

    record Parsed(PolicyBulkValidationResponse response, List<PublishPolicyRequest> requests) {}

    Parsed parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("The uploaded file is empty");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) throw new IllegalArgumentException("Only .xlsx files are supported");
        if (file.getSize() > MAX_FILE_BYTES) throw new IllegalArgumentException("The file is larger than 5 MB");

        Workbook wb;
        try (InputStream in = file.getInputStream()) {
            wb = new XSSFWorkbook(in);
        } catch (IOException | RuntimeException e) {
            // Renamed/corrupt file, password-protected workbook (an OLE2 container, not OOXML), zip-bomb
            // guard, ... — never echo POI internals.
            throw new IllegalArgumentException("Could not read the file. Upload a valid, unprotected .xlsx created from the template.");
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

        Map<String, Integer> cols = headerColumns(sheet.getRow(0), fmt, eval);
        Set<String> activeTitles = policyRepo.findActiveListItems().stream()
                .map(PolicyListItem::getTitle).collect(Collectors.toSet());
        Map<String, Integer> titleRowInFile = new HashMap<>();

        List<PolicyBulkValidationResponse.Row> rows = new ArrayList<>();
        List<PublishPolicyRequest> requests = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (isBlank(row, cols.values(), fmt, eval)) continue;
            if (rows.size() == MAX_ROWS) {
                throw new IllegalArgumentException("The file has more than " + MAX_ROWS + " policy rows. Split it into smaller files.");
            }
            int rowNumber = r + 1;
            List<CellError> errors = new ArrayList<>();

            String title = squash(cell(row, cols, TITLE, fmt, eval));
            if (title.isEmpty()) errors.add(new CellError(TITLE, "Policy title is required"));
            else if (title.length() > 200) errors.add(new CellError(TITLE, "Must be at most 200 characters"));
            else {
                Integer firstRow = titleRowInFile.putIfAbsent(title.toLowerCase(Locale.ROOT), rowNumber);
                if (firstRow != null) errors.add(new CellError(TITLE, "Duplicate of row " + firstRow + " in this file"));
            }

            String version = squash(cell(row, cols, VERSION, fmt, eval));
            if (version.isEmpty()) errors.add(new CellError(VERSION, "Version is required"));
            else if (version.length() > 20) errors.add(new CellError(VERSION, "Must be at most 20 characters"));

            // Line breaks are meaningful in the policy text — only trim the ends.
            String description = cell(row, cols, DESCRIPTION, fmt, eval).strip();
            if (description.isEmpty()) errors.add(new CellError(DESCRIPTION, "Description is required"));

            String audience = normalizeAudience(squash(cell(row, cols, AUDIENCE, fmt, eval)), errors);
            Boolean required = parseBoolean(squash(cell(row, cols, ACK, fmt, eval)), errors);

            rows.add(new PolicyBulkValidationResponse.Row(rowNumber, title, version, description, audience, required,
                    activeTitles.contains(title), errors));
            if (errors.isEmpty()) {
                PublishPolicyRequest req = new PublishPolicyRequest();
                req.setTitle(title);
                req.setVersion(version);
                req.setDescription(description);
                req.setAudience(audience);
                req.setRequired(required);
                requests.add(req);
            }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("No policy rows found in the '" + sheet.getSheetName() + "' sheet");

        int invalid = rows.size() - requests.size();
        return new Parsed(new PolicyBulkValidationResponse(rows.size(), requests.size(), invalid, rows), requests);
    }

    /** Header name → column index; rejects duplicate headers and missing mandatory columns. Unknown columns are ignored. */
    private static Map<String, Integer> headerColumns(Row header, DataFormatter fmt, FormulaEvaluator eval) {
        Map<String, Integer> cols = new HashMap<>();
        if (header != null) {
            for (Cell c : header) {
                String h = squash(text(c, fmt, eval).replace("*", ""));
                String known = Arrays.stream(HEADERS).filter(k -> k.equalsIgnoreCase(h)).findFirst().orElse(null);
                if (known != null && cols.putIfAbsent(known, c.getColumnIndex()) != null) {
                    throw new IllegalArgumentException("Column '" + known + "' appears more than once in the header row");
                }
            }
        }
        List<String> missing = Arrays.stream(HEADERS).filter(REQUIRED_HEADERS::contains).filter(h -> !cols.containsKey(h)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required column(s): " + String.join(", ", missing)
                    + ". Download the latest template.");
        }
        return cols;
    }

    /** Empty / ALL / All Employees → "All Employees"; otherwise known role codes joined with "," (same format the UI saves). */
    static String normalizeAudience(String raw, List<CellError> errors) {
        if (raw.isEmpty() || raw.equalsIgnoreCase("ALL") || raw.equalsIgnoreCase(ALL_EMPLOYEES)) return ALL_EMPLOYEES;
        Set<String> codes = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String code = p.toUpperCase(Locale.ROOT).replace(' ', '_'); // "HR Admin" → HR_ADMIN
            if (PolicyService.ALL_ROLE_CODES.contains(code)) codes.add(code); else unknown.add(p);
        }
        if (!unknown.isEmpty()) {
            errors.add(new CellError(AUDIENCE, "Unknown audience '" + String.join("', '", unknown)
                    + "'. Use All Employees or " + String.join(", ", ROLE_CODES)));
            return raw;
        }
        if (codes.isEmpty()) return ALL_EMPLOYEES;
        return codes.containsAll(PolicyService.ALL_ROLE_CODES) ? ALL_EMPLOYEES : String.join(",", codes);
    }

    static Boolean parseBoolean(String raw, List<CellError> errors) {
        switch (raw.toUpperCase(Locale.ROOT)) {
            case "", "TRUE", "YES", "Y", "1": return true;
            case "FALSE", "NO", "N", "0": return false;
            default:
                errors.add(new CellError(ACK, "Must be TRUE or FALSE"));
                return null;
        }
    }

    // ── Cell helpers ──────────────────────────────────────

    private static String cell(Row row, Map<String, Integer> cols, String header, DataFormatter fmt, FormulaEvaluator eval) {
        Integer i = cols.get(header);
        return i == null ? "" : text(row.getCell(i), fmt, eval);
    }

    private static String text(Cell c, DataFormatter fmt, FormulaEvaluator eval) {
        if (c == null) return "";
        try {
            return fmt.formatCellValue(c, eval);
        } catch (RuntimeException e) {
            // Unsupported/broken formula: fall back to the raw formula text, which then fails validation.
            return fmt.formatCellValue(c);
        }
    }

    /** Trims and collapses whitespace runs (incl. non-breaking spaces) to a single space. */
    static String squash(String s) {
        return s.replaceAll("[\\s\\u00A0]+", " ").strip();
    }

    private static boolean isBlank(Row row, Collection<Integer> cols, DataFormatter fmt, FormulaEvaluator eval) {
        if (row == null) return true;
        for (int i : cols) if (!squash(text(row.getCell(i), fmt, eval)).isEmpty()) return false;
        return true;
    }
}

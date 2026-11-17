package com.nforce.onehr.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.Set;

/**
 * Shared look for the bulk-upload .xlsx templates (Assets, Policies): row 1 is a merged, centered title
 * band, row 2 the column headers (grey, bold, mandatory ones in red) with filter buttons. Data starts on
 * row 3 — {@link #FIRST_DATA_ROW}. The parsers locate the header row themselves, so files made from the
 * older single-header-row template still import.
 */
public final class ExcelTemplateHeader {

    public static final int HEADER_ROW = 1;
    public static final int FIRST_DATA_ROW = 2;

    private static final byte[] TITLE_FILL = {(byte) 0xAD, (byte) 0xD8, (byte) 0xE6}; // light blue
    private static final byte[] HEADER_FILL = {(byte) 0xD9, (byte) 0xD9, (byte) 0xD9}; // light grey
    private static final byte[] MANDATORY_RED = {(byte) 0xFF, 0x00, 0x00};

    private ExcelTemplateHeader() {}

    public static void write(XSSFWorkbook wb, Sheet sheet, String title, String[] headers, Set<String> mandatory) {
        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(30);
        XSSFCellStyle titleStyle = style(wb, TITLE_FILL, null, 13, HorizontalAlignment.CENTER);
        for (int i = 0; i < headers.length; i++) titleRow.createCell(i).setCellStyle(titleStyle);
        titleRow.getCell(0).setCellValue(title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.length - 1));

        Row headerRow = sheet.createRow(HEADER_ROW);
        headerRow.setHeightInPoints(24);
        XSSFCellStyle plain = style(wb, HEADER_FILL, null, 11, HorizontalAlignment.LEFT);
        XSSFCellStyle required = style(wb, HEADER_FILL, MANDATORY_RED, 11, HorizontalAlignment.LEFT);
        for (int i = 0; i < headers.length; i++) {
            Cell c = headerRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(mandatory.contains(headers[i]) ? required : plain);
        }
        sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, HEADER_ROW, 0, headers.length - 1));
        sheet.createFreezePane(0, FIRST_DATA_ROW);
    }

    private static XSSFCellStyle style(XSSFWorkbook wb, byte[] fill, byte[] fontColor, int fontSize, HorizontalAlignment align) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(new XSSFColor(fill, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(align);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) fontSize);
        if (fontColor != null) font.setColor(new XSSFColor(fontColor, null));
        s.setFont(font);
        return s;
    }
}

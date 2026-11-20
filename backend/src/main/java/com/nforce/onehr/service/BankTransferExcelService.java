package com.nforce.onehr.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Bank-details ticket (Keka-walkthrough amendments) — the "Bank bulk-upload sheet": bank details
 * and net pay only, nothing else, for whoever Finance can actually hand to the bank this cycle.
 * {@link PayrollRunService#bankTransferExcel} resolves which employees qualify (skips anyone on a
 * Payout Hold, skips anyone missing bank details) and hands this class only the rows that made it.
 * No encryption/masking decision is made here — this file renders exactly what it's given; see
 * {@code EmployeeBankDetail} ticket notes for the still-open encryption-at-rest question.
 */
@Service
public class BankTransferExcelService {

    /** One row per employee who is actually getting paid this cycle and has bank details on file. */
    public record BankTransferRow(String accountHolderName, String accountNumber, String ifsc, BigDecimal netPay) {}

    public record Model(List<BankTransferRow> rows) {}

    public byte[] render(Model m) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = boldStyle(wb);
            CellStyle money = moneyStyle(wb);
            Sheet sheet = wb.createSheet("Bank Transfer");

            List<String> headers = List.of("Account Holder Name", "Account Number", "IFSC Code", "Net Pay");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(bold);
                sheet.setColumnWidth(i, Math.max(14, headers.get(i).length() + 4) * 256);
            }

            int r = 1;
            for (BankTransferRow row : m.rows()) {
                Row sheetRow = sheet.createRow(r++);
                sheetRow.createCell(0).setCellValue(row.accountHolderName());
                sheetRow.createCell(1).setCellValue(row.accountNumber());
                sheetRow.createCell(2).setCellValue(row.ifsc());
                Cell netCell = sheetRow.createCell(3);
                netCell.setCellValue(row.netPay().doubleValue());
                netCell.setCellStyle(money);
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build bank transfer workbook", e);
        }
    }

    private CellStyle boldStyle(XSSFWorkbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle moneyStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return style;
    }
}

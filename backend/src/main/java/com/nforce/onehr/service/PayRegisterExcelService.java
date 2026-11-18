package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Payslip;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pay Register export — the Payslips tab's "Export to Excel" button. One row per employee in a
 * payroll run, every distinct earning/deduction/contribution component in that run as its own
 * column (blank where a given employee has no line item for it), the same shape a real HR "pay
 * register" spreadsheet takes. Pure rendering — same split {@link PayslipPdfService} has from
 * {@link PayslipService}: {@link PayrollRunService} assembles the data, this only knows how to lay
 * it out as a workbook.
 */
@Service
public class PayRegisterExcelService {

    /** Everything needed to render one Pay Register — column sets are passed in already resolved
     * (ordered by the salary component catalog's own displayOrder) so this class never touches the
     * catalog itself. */
    public record Model(List<Payslip> payslips, Map<UUID, Employee> employees,
                         Map<UUID, Map<String, BigDecimal>> amountsByEmployee,
                         List<String> earningColumns, List<String> deductionColumns, List<String> contributionColumns) {}

    public byte[] render(Model m) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = boldStyle(wb);
            CellStyle money = moneyStyle(wb);
            Sheet sheet = wb.createSheet("Pay Register");

            List<String> headers = new ArrayList<>(List.of(
                    "Employee Name", "Employee Code", "Department", "Designation",
                    "Days in Cycle", "Days Employed", "LOP Days"));
            headers.addAll(m.earningColumns());
            headers.add("Gross Pay");
            headers.addAll(m.deductionColumns());
            headers.add("Total Deductions");
            headers.addAll(m.contributionColumns());
            headers.add("Net Pay");
            headers.add("Payment Withheld");

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(bold);
                sheet.setColumnWidth(i, Math.max(12, headers.get(i).length() + 4) * 256);
            }

            int r = 1;
            for (Payslip p : m.payslips()) {
                Employee e = m.employees().get(p.getEmployeeId());
                Map<String, BigDecimal> amounts = m.amountsByEmployee().getOrDefault(p.getEmployeeId(), Map.of());
                Row row = sheet.createRow(r++);
                int col = 0;
                textCell(row, col++, e == null ? "-" : e.getFullName());
                textCell(row, col++, e == null ? "-" : e.getEmployeeCode());
                textCell(row, col++, e == null || e.getDepartment() == null ? "-" : e.getDepartment().getName());
                textCell(row, col++, e == null || e.getDesignation() == null ? "-" : e.getDesignation().getTitle());
                numberCell(row, col++, BigDecimal.valueOf(p.getCycleDays()), null);
                numberCell(row, col++, BigDecimal.valueOf(p.getEmployedDays()), null);
                numberCell(row, col++, p.getLopDays(), null);
                for (String name : m.earningColumns()) numberCell(row, col++, amounts.get(name), money);
                numberCell(row, col++, p.getGrossPay(), money);
                for (String name : m.deductionColumns()) numberCell(row, col++, amounts.get(name), money);
                numberCell(row, col++, p.getTotalDeductions(), money);
                for (String name : m.contributionColumns()) numberCell(row, col++, amounts.get(name), money);
                numberCell(row, col++, p.getNetPay(), money);
                textCell(row, col, p.isPaymentWithheld() ? "Yes" : "No");
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build pay register workbook", e);
        }
    }

    private void textCell(Row row, int col, String value) {
        row.createCell(col).setCellValue(value);
    }

    /** No cell at all when {@code value} is null — an employee with no line item for this run's
     * component leaves a genuinely empty cell, not a zero. */
    private void numberCell(Row row, int col, BigDecimal value, CellStyle style) {
        if (value == null) return;
        Cell c = row.createCell(col);
        c.setCellValue(value.doubleValue());
        if (style != null) c.setCellStyle(style);
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

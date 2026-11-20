package com.nforce.onehr.service;

import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Designation;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Payslip;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure rendering test — no mocks, just build a Model and read the resulting workbook back. */
class PayRegisterExcelServiceTest {

    private final PayRegisterExcelService service = new PayRegisterExcelService();
    private final UUID employeeId = UUID.randomUUID();

    @Test
    void render_headerRow_ordersColumnsEarningsThenGrossThenDeductionsThenNet() throws Exception {
        Payslip payslip = Payslip.builder().id(UUID.randomUUID()).employeeId(employeeId)
                .cycleDays(30).employedDays(30).lopDays(BigDecimal.ZERO)
                .grossPay(new BigDecimal("50000.00")).totalDeductions(new BigDecimal("3800.00")).netPay(new BigDecimal("46200.00"))
                .build();
        Employee employee = new Employee();
        employee.setUserId(employeeId);
        employee.setFullName("Asha Rao");
        employee.setEmployeeCode("EMP001");
        employee.setDepartment(Department.builder().name("Engineering").build());
        employee.setDesignation(Designation.builder().title("Engineer").build());

        byte[] xlsx = service.render(new PayRegisterExcelService.Model(
                List.of(payslip), Map.of(employeeId, employee),
                Map.of(employeeId, Map.of("Basic", new BigDecimal("30000.00"), "PF", new BigDecimal("3600.00"), "Employer PF", new BigDecimal("3600.00"))),
                List.of("Basic"), List.of("PF"), List.of("Employer PF"), 1));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals("Pay Register — Draft 1", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());

            Row header = wb.getSheetAt(0).getRow(1);
            List<String> headers = new java.util.ArrayList<>();
            for (int i = 0; i < header.getLastCellNum(); i++) headers.add(header.getCell(i).getStringCellValue());
            assertEquals(List.of("Employee Name", "Employee Code", "Department", "Designation",
                    "Days in Cycle", "Days Employed", "LOP Days", "Basic", "Gross Pay", "PF", "Total Deductions",
                    "Employer PF", "Net Pay", "Payment Withheld"), headers);

            Row row = wb.getSheetAt(0).getRow(2);
            assertEquals("Asha Rao", row.getCell(0).getStringCellValue());
            assertEquals("EMP001", row.getCell(1).getStringCellValue());
            assertEquals("Engineering", row.getCell(2).getStringCellValue());
            assertEquals("Engineer", row.getCell(3).getStringCellValue());
            assertEquals(30000.00, row.getCell(7).getNumericCellValue(), 0.001); // Basic
            assertEquals(50000.00, row.getCell(8).getNumericCellValue(), 0.001); // Gross Pay
            assertEquals(3600.00, row.getCell(9).getNumericCellValue(), 0.001); // PF
            assertEquals(3800.00, row.getCell(10).getNumericCellValue(), 0.001); // Total Deductions
            assertEquals(3600.00, row.getCell(11).getNumericCellValue(), 0.001); // Employer PF
            assertEquals(46200.00, row.getCell(12).getNumericCellValue(), 0.001); // Net Pay
            assertEquals("No", row.getCell(13).getStringCellValue()); // Payment Withheld
        }
    }

    @Test
    void render_employeeWithNoLineItemForAColumn_leavesThatCellBlank() throws Exception {
        UUID otherEmployeeId = UUID.randomUUID();
        Payslip payslip = Payslip.builder().id(UUID.randomUUID()).employeeId(otherEmployeeId)
                .cycleDays(30).employedDays(30).lopDays(BigDecimal.ZERO)
                .grossPay(new BigDecimal("20000.00")).totalDeductions(BigDecimal.ZERO).netPay(new BigDecimal("20000.00"))
                .build();
        Employee employee = new Employee();
        employee.setUserId(otherEmployeeId);
        employee.setFullName("Ravi Kumar");
        employee.setEmployeeCode("EMP002");

        byte[] xlsx = service.render(new PayRegisterExcelService.Model(
                List.of(payslip), Map.of(otherEmployeeId, employee),
                Map.of(otherEmployeeId, Map.of("Basic", new BigDecimal("20000.00"))),
                List.of("Basic", "Bonus"), List.of(), List.of(), 2));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row row = wb.getSheetAt(0).getRow(2);
            assertEquals(20000.00, row.getCell(7).getNumericCellValue(), 0.001); // Basic
            assertNull(row.getCell(8)); // Bonus — no line item for this employee, cell never created
        }
    }
}

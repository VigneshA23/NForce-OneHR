package com.nforce.onehr.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.nforce.onehr.service.PayslipPdfService.Line;
import com.nforce.onehr.service.PayslipPdfService.PayslipPdfModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** US-C12: the renderer produces a real, readable PDF for representative and edge-case payslips. */
class PayslipPdfServiceTest {

    private final PayslipPdfService service = new PayslipPdfService();

    @Test
    void render_producesAReadablePdfCarryingTheKeyFigures() throws IOException {
        byte[] pdf = service.render(model(
                List.of(new Line("Basic", bd("50000.00")), new Line("HRA", bd("20000.00")),
                        new Line("Conveyance Allowance", bd("1600.00"))),
                List.of(new Line("Professional Tax", bd("200.00")), new Line("Advance Recovery", bd("1000.00"))),
                bd("71600.00"), bd("1200.00"), bd("70400.00")));

        assertTrue(pdf.length > 0);
        assertEquals("%PDF", new String(pdf, 0, 4));
        String text = text(pdf);
        assertTrue(text.contains("Payslip for November 2026"), text);
        assertTrue(text.contains("Asha Rao") && text.contains("EMP001"), text);
        assertTrue(text.contains("Basic") && text.contains("50,000.00"), text);
        assertTrue(text.contains("Professional Tax"), text);
        assertTrue(text.contains("71,600.00") && text.contains("1,200.00") && text.contains("70,400.00"), text);
    }

    @Test
    void render_neverPrintsCtc() throws IOException {
        // The model has no CTC/employer-contribution field at all; assert the output reflects that.
        String text = text(service.render(model(List.of(new Line("Basic", bd("100.00"))), List.of(),
                bd("100.00"), bd("0"), bd("100.00"))));

        assertFalse(text.toLowerCase().contains("ctc"), text);
        assertFalse(text.toLowerCase().contains("gratuity"), text);
        assertFalse(text.toLowerCase().contains("employer"), text);
    }

    @Test
    void render_roundsHalfUpToTwoDecimalsAndGroupsThousands() throws IOException {
        String text = text(service.render(model(
                List.of(new Line("Basic", bd("1234567.895")), new Line("Tiny", bd("0.005"))),
                List.of(new Line("Rounding Down", bd("0.004"))),
                bd("1234567.90"), bd("0.004"), bd("1234567.90"))));

        assertTrue(text.contains("1,234,567.90"), text);
        assertTrue(text.contains("0.01"), text);
        assertTrue(text.contains("0.00"), text);
    }

    @Test
    void render_handlesNoDeductionsAndUnevenColumns() {
        assertDoesNotThrow(() -> service.render(model(
                List.of(new Line("Basic", bd("10.00")), new Line("HRA", bd("5.00"))), List.of(),
                bd("15.00"), bd("0.00"), bd("15.00"))));
        assertDoesNotThrow(() -> service.render(model(List.of(), List.of(new Line("Advance", bd("5.00"))),
                bd("0.00"), bd("5.00"), bd("-5.00"))));
    }

    @Test
    void render_flowsManyComponentsOntoFurtherPages() throws IOException {
        List<Line> earnings = new ArrayList<>();
        IntStream.range(0, 120).forEach(i -> earnings.add(new Line("Component " + i, bd("100.00"))));

        byte[] pdf = service.render(model(earnings, List.of(), bd("12000.00"), bd("0.00"), bd("12000.00")));

        assertTrue(new PdfReader(pdf).getNumberOfPages() > 1);
    }

    @Test
    void render_toleratesMissingOptionalHeaderFields() {
        PayslipPdfModel m = new PayslipPdfModel(null, "INR", "November 2026",
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 25), "Asha Rao", "EMP001",
                null, null, null, 31, 31, bd("0"), List.of(), List.of(), bd("0"), bd("0"), bd("0"));

        assertDoesNotThrow(() -> service.render(m));
    }

    @Test
    void render_showsDaysPayableAsEmployedMinusLop_neverNegative() throws IOException {
        PayslipPdfModel m = new PayslipPdfModel("NForce", "INR", "November 2026",
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 25), "Asha Rao", "EMP001",
                "Engineering", "Engineer", LocalDate.of(2024, 1, 1), 31, 20, bd("25"),
                List.of(), List.of(), bd("0"), bd("0"), bd("0"));

        String text = text(service.render(m));

        assertTrue(text.contains("Days Payable"), text);
        assertFalse(text.contains("-5"), text);
    }

    private PayslipPdfModel model(List<Line> earnings, List<Line> deductions,
                                  BigDecimal gross, BigDecimal totalDeductions, BigDecimal net) {
        return new PayslipPdfModel("NForce One Pvt Ltd", "INR", "November 2026",
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 25),
                "Asha Rao", "EMP001", "Engineering", "Senior Engineer", LocalDate.of(2024, 4, 1),
                31, 31, bd("1.5"), earnings, deductions, gross, totalDeductions, net);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            sb.append(extractor.getTextFromPage(p)).append('\n');
        }
        return sb.toString();
    }
}

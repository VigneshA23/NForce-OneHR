package com.nforce.onehr.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.nforce.onehr.service.PayslipPdfService.ArrearLine;
import com.nforce.onehr.service.PayslipPdfService.LeaveLine;
import com.nforce.onehr.service.PayslipPdfService.Line;
import com.nforce.onehr.service.PayslipPdfService.PayslipExtras;
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
        // The model has no CTC field at all, and no employer contribution is configured in this
        // test's extras; assert the output reflects that.
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
                null, null, null, 31, 31, bd("0"), List.of(), List.of(), bd("0"), bd("0"), bd("0"), null, null, null,
                PayslipExtras.none(), null);

        assertDoesNotThrow(() -> service.render(m));
    }

    @Test
    void render_showsDaysPayableAsEmployedMinusLop_neverNegative() throws IOException {
        PayslipPdfModel m = new PayslipPdfModel("NForce", "INR", "November 2026",
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 25), "Asha Rao", "EMP001",
                "Engineering", "Engineer", LocalDate.of(2024, 1, 1), 31, 20, bd("25"),
                List.of(), List.of(), bd("0"), bd("0"), bd("0"), null, null, null, PayslipExtras.none(), null);

        String text = text(service.render(m));

        assertTrue(text.contains("Days Payable"), text);
        assertFalse(text.contains("-5"), text);
    }

    @Test
    void render_withCustomHeaderAndFooterText_usesThemInsteadOfDefaults() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipPdfModel withText = new PayslipPdfModel(base.companyName(), base.currency(), base.periodLabel(),
                base.cycleStart(), base.cycleEnd(), base.employeeName(), base.employeeCode(), base.department(),
                base.designation(), base.joiningDate(), base.cycleDays(), base.employedDays(), base.lopDays(),
                base.earnings(), base.deductions(), base.grossPay(), base.totalDeductions(), base.netPay(),
                "PAYSLIP", "Custom footer note.", null, PayslipExtras.none(), null);

        String text = text(service.render(withText));

        assertTrue(text.contains("PAYSLIP November 2026"), text);
        assertTrue(text.contains("Custom footer note."), text);
        assertFalse(text.contains(DEFAULT_FOOTER_FOR_TEST), text);
    }

    @Test
    void render_withALogo_embedsItWithoutBreakingTheRestOfThePayslip() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipPdfModel withLogo = withLogo(base, ONE_PIXEL_PNG);

        byte[] pdf = service.render(withLogo);

        assertEquals("%PDF", new String(pdf, 0, 4));
        assertTrue(text(pdf).contains("Basic"));
    }

    @Test
    void render_withCorruptLogoBytes_stillRendersThePayslip() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipPdfModel withBadLogo = withLogo(base, new byte[] {1, 2, 3});

        byte[] pdf = service.render(withBadLogo);

        assertEquals("%PDF", new String(pdf, 0, 4));
        assertTrue(text(pdf).contains("Basic"));
    }

    // 1x1 transparent PNG — just enough bytes for Image.getInstance to decode successfully.
    private static final byte[] ONE_PIXEL_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    private PayslipPdfModel withLogo(PayslipPdfModel base, byte[] logo) {
        return new PayslipPdfModel(base.companyName(), base.currency(), base.periodLabel(),
                base.cycleStart(), base.cycleEnd(), base.employeeName(), base.employeeCode(), base.department(),
                base.designation(), base.joiningDate(), base.cycleDays(), base.employedDays(), base.lopDays(),
                base.earnings(), base.deductions(), base.grossPay(), base.totalDeductions(), base.netPay(),
                base.documentHeaderText(), base.footerText(), logo, base.extras(), base.companyAddress());
    }

    @Test
    void render_withCustomSectionHeadings_replacesEarningsAndDeductionsLabels() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(new Line("PT", bd("10.00"))), bd("100.00"), bd("10.00"), bd("90.00"));
        PayslipExtras extras = extras(false, "Income", "Outgoings", PayslipFields.DEFAULT_KEYS, null, null, null, "RIGHT", List.of(), List.of());
        PayslipPdfModel withHeadings = withExtras(base, extras);

        String text = text(service.render(withHeadings));

        assertTrue(text.contains("Income"), text);
        assertTrue(text.contains("Outgoings"), text);
        assertTrue(text.contains("Gross Income"), text);
        assertTrue(text.contains("Total Outgoings"), text);
        assertFalse(text.contains("Earnings"), text);
    }

    @Test
    void render_payslipFields_showsOnlyTheConfiguredIdentityFields() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipExtras extras = extras(false, null, null, List.of("uan", "pan"), "UAN999", null, "ABCDE1234F", "RIGHT", List.of(), List.of());
        PayslipPdfModel withFields = withExtras(base, extras);

        String text = text(service.render(withFields));

        assertTrue(text.contains("UAN999"), text);
        assertTrue(text.contains("ABCDE1234F"), text);
        assertFalse(text.contains("Department"), text);
        assertFalse(text.contains("Designation"), text);
    }

    @Test
    void render_paymentMode_printsWhenConfiguredAsAnIdentityField() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipPdfModel withField = withExtras(base, extrasWithPaymentMode(List.of("paymentMode"), "Bank Transfer"));

        String text = text(service.render(withField));

        assertTrue(text.contains("Payment Mode"), text);
        assertTrue(text.contains("Bank Transfer"), text);
    }

    @Test
    void render_excludeNaFields_dropsABlankFieldInsteadOfPrintingADash() throws IOException {
        PayslipPdfModel base = new PayslipPdfModel("NForce One Pvt Ltd", "INR", "November 2026",
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 25),
                "Asha Rao", "EMP001", null, null, null,
                31, 31, bd("0"), List.of(), List.of(), bd("0"), bd("0"), bd("0"), null, null, null,
                PayslipExtras.none(), null);
        PayslipExtras extras = extras(true, null, null, List.of("department", "pan"), null, null, "ABCDE1234F", "RIGHT", List.of(), List.of());
        PayslipPdfModel withExclude = withExtras(base, extras);

        String text = text(service.render(withExclude));

        assertTrue(text.contains("PAN Number"), text);
        assertTrue(text.contains("ABCDE1234F"), text);
        assertFalse(text.contains("Department"), text);
    }

    @Test
    void render_includeLeaveSummary_printsTheBalances() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipExtras extras = extras(false, null, null, PayslipFields.DEFAULT_KEYS, null, null, null, "RIGHT",
                List.of(new LeaveLine("Earned Leave", bd("12.5")), new LeaveLine("Sick Leave", bd("3"))), List.of());
        PayslipPdfModel withLeave = withExtras(base, extras);

        String text = text(service.render(withLeave));

        assertTrue(text.contains("Leave Summary"), text);
        assertTrue(text.contains("Earned Leave"), text);
        assertTrue(text.contains("12.5"), text);
        assertTrue(text.contains("Sick Leave"), text);
    }

    @Test
    void render_includeArrearBreakup_printsReasonPeriodAndAmount() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipExtras extras = extras(false, null, null, PayslipFields.DEFAULT_KEYS, null, null, null, "RIGHT",
                List.of(), List.of(new ArrearLine("Backdated salary revision", LocalDate.of(2026, 9, 26), LocalDate.of(2026, 10, 25), bd("5000.00"))));
        PayslipPdfModel withArrears = withExtras(base, extras);

        String text = text(service.render(withArrears));

        assertTrue(text.contains("Arrears Breakup"), text);
        assertTrue(text.contains("Backdated salary revision"), text);
        assertTrue(text.contains("5,000.00"), text);
    }

    @Test
    void render_logoAlignment_leftOrRight_bothRenderWithoutError() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipPdfModel withLogo = withLogo(base, ONE_PIXEL_PNG);

        PayslipPdfModel left = withExtras(withLogo, extras(false, null, null, PayslipFields.DEFAULT_KEYS, null, null, null, "LEFT", List.of(), List.of()));
        PayslipPdfModel right = withExtras(withLogo, extras(false, null, null, PayslipFields.DEFAULT_KEYS, null, null, null, "RIGHT", List.of(), List.of()));

        assertTrue(text(service.render(left)).contains("Basic"));
        assertTrue(text(service.render(right)).contains("Basic"));
    }

    @Test
    void render_logoSizeAndCircleShape_bothRenderWithoutError() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipPdfModel withLogo = withLogo(base, ONE_PIXEL_PNG);

        PayslipPdfModel square = withExtras(withLogo, extrasWithLogo("RIGHT", 120, "SQUARE"));
        PayslipPdfModel circle = withExtras(withLogo, extrasWithLogo("RIGHT", 120, "CIRCLE"));

        assertTrue(text(service.render(square)).contains("Basic"));
        assertTrue(text(service.render(circle)).contains("Basic"));
    }

    @Test
    void render_companyAddress_printsUnderTheCompanyName_withAndWithoutALogo() throws IOException {
        PayslipPdfModel base = model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00"));
        PayslipPdfModel withAddress = withAddress(base, "4th Floor, Example Towers, Hyderabad 500081");

        assertTrue(text(service.render(withAddress)).contains("4th Floor, Example Towers, Hyderabad 500081"));
        assertTrue(text(service.render(withLogo(withAddress, ONE_PIXEL_PNG))).contains("4th Floor, Example Towers, Hyderabad 500081"));
    }

    @Test
    void render_includeYtd_addsAYtdColumnWithEachLinesCumulativeTotal() throws IOException {
        PayslipPdfModel base = model(
                List.of(new Line("Basic", bd("100.00"), bd("1200.00"))),
                List.of(new Line("Professional Tax", bd("10.00"), bd("120.00"))),
                bd("100.00"), bd("10.00"), bd("90.00"));
        PayslipExtras withYtd = extras(false, null, null, PayslipFields.DEFAULT_KEYS, null, null, null, "RIGHT", List.of(), List.of(), true, List.of());
        PayslipPdfModel m = withExtras(base, withYtd);

        String text = text(service.render(m));

        assertTrue(text.contains("YTD"), text);
        assertTrue(text.contains("1,200.00"), text);
        assertTrue(text.contains("120.00"), text);
    }

    @Test
    void render_employerContributions_pairWithEarnings_andDeductionsGetTheirOwnTable() throws IOException {
        PayslipPdfModel base = model(
                List.of(new Line("Basic", bd("40000.00"))),
                List.of(new Line("Provident Fund", bd("3000.00"))),
                bd("40000.00"), bd("3000.00"), bd("37000.00"));
        PayslipExtras withContributions = extras(false, null, null, PayslipFields.DEFAULT_KEYS, null, null, null, "RIGHT",
                List.of(), List.of(), false, List.of(new Line("Employer PF", bd("3000.00"))));
        PayslipPdfModel m = withExtras(base, withContributions);

        String text = text(service.render(m));

        assertTrue(text.contains("Contributions"), text);
        assertTrue(text.contains("Employer PF"), text);
        assertTrue(text.contains("Total Contributions"), text);
        assertTrue(text.contains("Provident Fund"), text);
    }

    @Test
    void render_netPayInWords_alwaysPrints() throws IOException {
        String text = text(service.render(model(List.of(new Line("Basic", bd("37216.00"))), List.of(),
                bd("37216.00"), bd("0.00"), bd("37216.00"))));

        assertTrue(text.contains("Thirty Seven Thousand Two Hundred and Sixteen Rupees Only"), text);
    }

    private PayslipExtras extras(boolean excludeNaFields, String earningsHeading, String deductionsHeading,
                                 List<String> identityFields, String uan, String esi, String pan, String logoAlignment,
                                 List<LeaveLine> leaveSummary, List<ArrearLine> arrearBreakup) {
        return extras(excludeNaFields, earningsHeading, deductionsHeading, identityFields, uan, esi, pan, logoAlignment,
                leaveSummary, arrearBreakup, false, List.of());
    }

    private PayslipExtras extras(boolean excludeNaFields, String earningsHeading, String deductionsHeading,
                                 List<String> identityFields, String uan, String esi, String pan, String logoAlignment,
                                 List<LeaveLine> leaveSummary, List<ArrearLine> arrearBreakup, boolean includeYtd, List<Line> contributions) {
        return new PayslipExtras(excludeNaFields, earningsHeading, deductionsHeading, identityFields, uan, esi, pan, null,
                logoAlignment, 70, "SQUARE", leaveSummary, arrearBreakup, includeYtd, contributions);
    }

    private PayslipExtras extrasWithPaymentMode(List<String> identityFields, String paymentMode) {
        return new PayslipExtras(false, null, null, identityFields, null, null, null, paymentMode,
                "RIGHT", 70, "SQUARE", List.of(), List.of(), false, List.of());
    }

    private PayslipExtras extrasWithLogo(String logoAlignment, int logoSize, String logoShape) {
        return new PayslipExtras(false, null, null, PayslipFields.DEFAULT_KEYS, null, null, null, null,
                logoAlignment, logoSize, logoShape, List.of(), List.of(), false, List.of());
    }

    private PayslipPdfModel withExtras(PayslipPdfModel base, PayslipExtras extras) {
        return new PayslipPdfModel(base.companyName(), base.currency(), base.periodLabel(),
                base.cycleStart(), base.cycleEnd(), base.employeeName(), base.employeeCode(), base.department(),
                base.designation(), base.joiningDate(), base.cycleDays(), base.employedDays(), base.lopDays(),
                base.earnings(), base.deductions(), base.grossPay(), base.totalDeductions(), base.netPay(),
                base.documentHeaderText(), base.footerText(), base.logo(), extras, base.companyAddress());
    }

    private PayslipPdfModel withAddress(PayslipPdfModel base, String companyAddress) {
        return new PayslipPdfModel(base.companyName(), base.currency(), base.periodLabel(),
                base.cycleStart(), base.cycleEnd(), base.employeeName(), base.employeeCode(), base.department(),
                base.designation(), base.joiningDate(), base.cycleDays(), base.employedDays(), base.lopDays(),
                base.earnings(), base.deductions(), base.grossPay(), base.totalDeductions(), base.netPay(),
                base.documentHeaderText(), base.footerText(), base.logo(), base.extras(), companyAddress);
    }

    @Test
    void render_withPassword_producesAPdfThatRequiresItToOpen() {
        byte[] pdf = service.render(model(List.of(new Line("Basic", bd("100.00"))), List.of(), bd("100.00"), bd("0"), bd("100.00")), "ABCDE1234F");

        assertThrows(Exception.class, () -> new PdfReader(pdf)); // no password supplied
        assertDoesNotThrow(() -> new PdfReader(pdf, "ABCDE1234F".getBytes()));
    }

    private static final String DEFAULT_FOOTER_FOR_TEST = "This is a computer-generated payslip and does not require a signature.";

    private PayslipPdfModel model(List<Line> earnings, List<Line> deductions,
                                  BigDecimal gross, BigDecimal totalDeductions, BigDecimal net) {
        return new PayslipPdfModel("NForce One Pvt Ltd", "INR", "November 2026",
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 25),
                "Asha Rao", "EMP001", "Engineering", "Senior Engineer", LocalDate.of(2024, 4, 1),
                31, 31, bd("1.5"), earnings, deductions, gross, totalDeductions, net, null, null, null,
                PayslipExtras.none(), null);
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

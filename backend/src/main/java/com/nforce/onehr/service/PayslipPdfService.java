package com.nforce.onehr.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * US-C12 — renders one payslip to a PDF. Pure rendering: it takes an already-assembled {@link
 * PayslipPdfModel} (built by {@link PayslipService} from {@code Payslip}/{@code PayslipLineItem}
 * rows) and knows nothing about JPA or who is allowed to see it — that is {@link PayslipAccessPolicy}.
 *
 * <p>Deliberately never prints CTC, employer PF or gratuity: those are part of CTC and are shown only
 * in the salary-structure view, not on a payslip. The model has no field for them, so they cannot
 * leak in by accident.
 */
@Service
public class PayslipPdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
    private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font NOTE = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8);

    /** One component row on the earnings or deductions table. */
    public record Line(String name, BigDecimal amount) {}

    public record PayslipPdfModel(String companyName, String currency, String periodLabel,
                                  LocalDate cycleStart, LocalDate cycleEnd,
                                  String employeeName, String employeeCode, String department,
                                  String designation, LocalDate joiningDate,
                                  int cycleDays, int employedDays, BigDecimal lopDays,
                                  List<Line> earnings, List<Line> deductions,
                                  BigDecimal grossPay, BigDecimal totalDeductions, BigDecimal netPay) {}

    public byte[] render(PayslipPdfModel m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Paragraph company = new Paragraph(orDash(m.companyName()), TITLE);
            company.setAlignment(Element.ALIGN_CENTER);
            doc.add(company);
            Paragraph title = new Paragraph("Payslip for " + m.periodLabel(), HEADING);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(10);
            doc.add(title);

            doc.add(employeeTable(m));
            doc.add(spacer());
            doc.add(daysTable(m));
            doc.add(spacer());
            doc.add(componentsTable(m));
            doc.add(spacer());
            doc.add(totalsTable(m));

            Paragraph note = new Paragraph("This is a computer-generated payslip and does not require a signature.", NOTE);
            note.setSpacingBefore(14);
            doc.add(note);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render payslip PDF", e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    private PdfPTable employeeTable(PayslipPdfModel m) {
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        row(t, "Employee Name", m.employeeName(), "Employee No.", m.employeeCode());
        row(t, "Department", m.department(), "Designation", m.designation());
        row(t, "Date of Joining", m.joiningDate() == null ? null : DATE.format(m.joiningDate()),
                "Pay Period", DATE.format(m.cycleStart()) + " - " + DATE.format(m.cycleEnd()));
        return t;
    }

    private PdfPTable daysTable(PayslipPdfModel m) {
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        BigDecimal payable = BigDecimal.valueOf(m.employedDays()).subtract(m.lopDays()).max(BigDecimal.ZERO);
        row(t, "Days in Cycle", String.valueOf(m.cycleDays()), "Days Employed", String.valueOf(m.employedDays()));
        row(t, "LOP Days", plain(m.lopDays()), "Days Payable", plain(payable));
        return t;
    }

    private PdfPTable componentsTable(PayslipPdfModel m) {
        PdfPTable t = new PdfPTable(new float[] {3f, 1.5f, 3f, 1.5f});
        t.setWidthPercentage(100);
        header(t, "Earnings");
        header(t, "Amount (" + m.currency() + ")");
        header(t, "Deductions");
        header(t, "Amount (" + m.currency() + ")");
        int rows = Math.max(m.earnings().size(), m.deductions().size());
        for (int i = 0; i < rows; i++) {
            Line earning = i < m.earnings().size() ? m.earnings().get(i) : null;
            Line deduction = i < m.deductions().size() ? m.deductions().get(i) : null;
            cell(t, earning == null ? "" : earning.name(), BODY, Element.ALIGN_LEFT);
            cell(t, earning == null ? "" : money(earning.amount()), BODY, Element.ALIGN_RIGHT);
            cell(t, deduction == null ? "" : deduction.name(), BODY, Element.ALIGN_LEFT);
            cell(t, deduction == null ? "" : money(deduction.amount()), BODY, Element.ALIGN_RIGHT);
        }
        cell(t, "Gross Earnings", BOLD, Element.ALIGN_LEFT);
        cell(t, money(m.grossPay()), BOLD, Element.ALIGN_RIGHT);
        cell(t, "Total Deductions", BOLD, Element.ALIGN_LEFT);
        cell(t, money(m.totalDeductions()), BOLD, Element.ALIGN_RIGHT);
        return t;
    }

    private PdfPTable totalsTable(PayslipPdfModel m) {
        PdfPTable t = new PdfPTable(new float[] {6f, 3f});
        t.setWidthPercentage(100);
        PdfPCell label = new PdfPCell(new Phrase("Net Pay (" + m.currency() + ")", HEADING));
        label.setPadding(6);
        PdfPCell value = new PdfPCell(new Phrase(money(m.netPay()), HEADING));
        value.setPadding(6);
        value.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(label);
        t.addCell(value);
        return t;
    }

    private void row(PdfPTable t, String l1, String v1, String l2, String v2) {
        borderless(t, l1, BOLD);
        borderless(t, orDash(v1), BODY);
        borderless(t, l2, BOLD);
        borderless(t, orDash(v2), BODY);
    }

    private void borderless(PdfPTable t, String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(3);
        t.addCell(c);
    }

    private void header(PdfPTable t, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, BOLD));
        c.setPadding(5);
        c.setBackgroundColor(new java.awt.Color(230, 230, 230));
        t.addCell(c);
    }

    private void cell(PdfPTable t, String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(5);
        c.setHorizontalAlignment(align);
        t.addCell(c);
    }

    private Paragraph spacer() {
        Paragraph p = new Paragraph(" ", BODY);
        p.setSpacingAfter(2);
        return p;
    }

    /** Two decimal places, half-up, thousands-grouped — locale-independent so output never varies by server locale. */
    static String money(BigDecimal amount) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(amount == null ? BigDecimal.ZERO : amount);
    }

    private static String plain(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}

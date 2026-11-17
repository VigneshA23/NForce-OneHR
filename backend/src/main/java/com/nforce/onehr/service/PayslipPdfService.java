package com.nforce.onehr.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
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
import java.util.ArrayList;
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
    private static final Font ADDRESS = FontFactory.getFont(FontFactory.HELVETICA, 8);

    /** One component row on the earnings, deductions or contributions table. {@code ytd} is null
     * unless the "Include YTD" setting is on; the 2-arg constructor keeps every existing caller
     * that never heard of YTD unchanged. */
    public record Line(String name, BigDecimal amount, BigDecimal ytd) {
        public Line(String name, BigDecimal amount) {
            this(name, amount, null);
        }
    }

    /** One row of the optional Leave Summary addendum — a real leave balance as of the run's
     * cycle end, not a payslip-specific concept. */
    public record LeaveLine(String leaveType, BigDecimal balance) {}

    /** One row of the optional Arrears Breakup addendum — one real {@code Arrears} record applied
     * to this run, broken out under the single consolidated "Arrears"/"Arrears Recovery" line item
     * it was folded into. */
    public record ArrearLine(String reason, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount) {}

    /** Everything Keka restyle's "Body/Header Customisation" screen (§3.9) controls, bundled so
     * adding one of these settings doesn't mean threading a new positional field through every
     * existing {@link PayslipPdfModel} call site. {@code none()} is exactly today's long-standing
     * behaviour — every pay group that's never touched these settings renders unchanged. */
    public record PayslipExtras(boolean excludeNaFields, String earningsHeading, String deductionsHeading,
                                 List<String> identityFields, String uanNumber, String esiNumber, String panNumber,
                                 String logoAlignment, List<LeaveLine> leaveSummary, List<ArrearLine> arrearBreakup,
                                 boolean includeYtd, List<Line> contributions) {
        public static PayslipExtras none() {
            return new PayslipExtras(false, null, null, PayslipFields.DEFAULT_KEYS, null, null, null,
                    "RIGHT", List.of(), List.of(), false, List.of());
        }
    }

    // documentHeaderText/footerText default to null — Keka restyle's "Configure Payslip
    // Settings" (§3.9) overrides them per pay group; a payslip whose pay group never configured
    // either (or didn't exist before this setting did) renders with exactly the same hardcoded
    // text this always used, not a sudden blank or placeholder.
    // logo defaults to null — a pay group with none configured renders exactly as it always
    // did, company name only, no blank space reserved for a logo that was never uploaded.
    // companyAddress defaults to null — printed under the company name only when the pay group
    // has one on file.
    // extras defaults to PayslipExtras.none() at every call site — see that record's own doc.
    public record PayslipPdfModel(String companyName, String currency, String periodLabel,
                                  LocalDate cycleStart, LocalDate cycleEnd,
                                  String employeeName, String employeeCode, String department,
                                  String designation, LocalDate joiningDate,
                                  int cycleDays, int employedDays, BigDecimal lopDays,
                                  List<Line> earnings, List<Line> deductions,
                                  BigDecimal grossPay, BigDecimal totalDeductions, BigDecimal netPay,
                                  String documentHeaderText, String footerText, byte[] logo,
                                  PayslipExtras extras, String companyAddress) {}

    private static final String DEFAULT_TITLE_PREFIX = "Payslip for";
    private static final String DEFAULT_FOOTER = "This is a computer-generated payslip and does not require a signature.";

    public byte[] render(PayslipPdfModel m) {
        return render(m, null);
    }

    /** {@code password}, when non-blank, encrypts the PDF so it can't be opened without it — Keka
     * restyle's "Enable password protection for payslips" setting (§3.9), keyed by the
     * employee's own PAN (matching that screen's own documented convention: "the password to
     * open Payslip will be the PAN number ... in uppercase"), resolved by the caller, not here —
     * this method only knows it received a password, never what it's derived from. */
    public byte[] render(PayslipPdfModel m, String password) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            if (password != null && !password.isBlank()) {
                writer.setEncryption(password.getBytes(), password.getBytes(),
                        com.lowagie.text.pdf.PdfWriter.ALLOW_PRINTING, com.lowagie.text.pdf.PdfWriter.STANDARD_ENCRYPTION_128);
            }
            doc.open();

            addHeader(doc, m);

            doc.add(employeeTable(m));
            doc.add(spacer());
            doc.add(daysTable(m));
            doc.add(spacer());
            for (Element e : componentsTables(m)) {
                doc.add(e);
            }
            doc.add(spacer());
            doc.add(totalsTable(m));

            Paragraph netPayWords = new Paragraph("Net Pay in Words: " + AmountInWords.inWords(m.netPay()), NOTE);
            netPayWords.setSpacingBefore(4);
            doc.add(netPayWords);

            PayslipExtras extras = m.extras() != null ? m.extras() : PayslipExtras.none();
            if (!extras.leaveSummary().isEmpty()) {
                doc.add(spacer());
                doc.add(leaveSummaryTable(extras));
            }
            if (!extras.arrearBreakup().isEmpty()) {
                doc.add(spacer());
                doc.add(arrearBreakupTable(extras));
            }

            String footerText = m.footerText() != null && !m.footerText().isBlank() ? m.footerText() : DEFAULT_FOOTER;
            Paragraph note = new Paragraph(footerText, NOTE);
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

    /** No logo configured: exactly the original centered company-name-then-title layout, byte
     * for byte what every pay group rendered before this setting existed. With a logo: a
     * borderless two-column table puts the logo on whichever side {@code logoAlignment} says
     * (Keka restyle §3.9's "Company Logo Alignment"), company name and title stacked in the other
     * column. Any failure to decode the stored bytes (a corrupt upload) is swallowed and falls
     * back to the no-logo layout — a bad logo must never take the whole payslip down with it. */
    private void addHeader(Document doc, PayslipPdfModel m) throws DocumentException {
        String titlePrefix = m.documentHeaderText() != null && !m.documentHeaderText().isBlank()
                ? m.documentHeaderText() : DEFAULT_TITLE_PREFIX;
        String titleText = titlePrefix + " " + m.periodLabel();

        if (m.logo() != null && m.logo().length > 0) {
            try {
                Image image = Image.getInstance(m.logo());
                image.scaleToFit(70, 50);

                Paragraph textBlock = new Paragraph();
                textBlock.add(new Phrase(titleText + "\n", HEADING));
                textBlock.add(new Phrase(orDash(m.companyName()), TITLE));
                if (m.companyAddress() != null && !m.companyAddress().isBlank()) {
                    textBlock.add(new Phrase("\n" + m.companyAddress(), ADDRESS));
                }

                PdfPCell textCell = new PdfPCell();
                textCell.setBorder(Rectangle.NO_BORDER);
                textCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                textCell.addElement(textBlock);

                PdfPCell logoCell = new PdfPCell(image, false);
                logoCell.setBorder(Rectangle.NO_BORDER);
                logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                logoCell.setHorizontalAlignment("LEFT".equals(m.extras() != null ? m.extras().logoAlignment() : null)
                        ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);

                PdfPTable header = new PdfPTable(new float[] {3f, 1f});
                header.setWidthPercentage(100);
                header.setSpacingAfter(10);
                boolean logoOnLeft = m.extras() != null && "LEFT".equals(m.extras().logoAlignment());
                if (logoOnLeft) { header.addCell(logoCell); header.addCell(textCell); }
                else { header.addCell(textCell); header.addCell(logoCell); }
                doc.add(header);
                return;
            } catch (Exception ignored) {
                // corrupt or unsupported image bytes — fall through to the no-logo layout
            }
        }

        Paragraph company = new Paragraph(orDash(m.companyName()), TITLE);
        company.setAlignment(Element.ALIGN_CENTER);
        doc.add(company);
        if (m.companyAddress() != null && !m.companyAddress().isBlank()) {
            Paragraph address = new Paragraph(m.companyAddress(), ADDRESS);
            address.setAlignment(Element.ALIGN_CENTER);
            doc.add(address);
        }
        Paragraph title = new Paragraph(titleText, HEADING);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingBefore(4);
        title.setSpacingAfter(10);
        doc.add(title);
    }

    /** Employee Name, Employee Number and Pay Period always print — Keka restyle's "Payslip
     * Fields" screen (§3.9) only lets HR toggle/reorder the other six ({@link PayslipFields}).
     * "Exclude N/A Payslip Fields" drops a blank one from the layout entirely instead of
     * printing "-" for it — the only one of these settings that changes which cells exist at all,
     * not just what's in them. */
    private PdfPTable employeeTable(PayslipPdfModel m) {
        PayslipExtras extras = m.extras() != null ? m.extras() : PayslipExtras.none();
        List<String> keys = extras.identityFields() != null && !extras.identityFields().isEmpty()
                ? extras.identityFields() : PayslipFields.DEFAULT_KEYS;

        List<String[]> pairs = new ArrayList<>();
        for (String key : keys) {
            String value = switch (key) {
                case "dateOfJoining" -> m.joiningDate() == null ? null : DATE.format(m.joiningDate());
                case "department" -> m.department();
                case "designation" -> m.designation();
                case "uan" -> extras.uanNumber();
                case "esi" -> extras.esiNumber();
                case "pan" -> extras.panNumber();
                default -> null;
            };
            if (extras.excludeNaFields() && (value == null || value.isBlank())) continue;
            pairs.add(new String[] { PayslipFields.LABEL.getOrDefault(key, key), value });
        }
        pairs.add(new String[] { "Pay Period", DATE.format(m.cycleStart()) + " - " + DATE.format(m.cycleEnd()) });

        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        row(t, "Employee Name", m.employeeName(), "Employee No.", m.employeeCode());
        for (int i = 0; i < pairs.size(); i += 2) {
            String[] a = pairs.get(i);
            String[] b = i + 1 < pairs.size() ? pairs.get(i + 1) : new String[] { "", "" };
            row(t, a[0], a[1], b[0], b[1]);
        }
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

    /** No employer contributions configured: exactly the original 2-section (Earnings|Deductions)
     * table, byte for byte what every payslip rendered before Contributions existed as a concept
     * — "Include YTD" aside, which independently adds a third column to each section. With
     * contributions: matches Keka's own layout — Earnings pairs with Contributions in one table,
     * Deductions gets its own table below, rather than trying to force three sections into one
     * row. */
    private List<Element> componentsTables(PayslipPdfModel m) {
        PayslipExtras extras = m.extras() != null ? m.extras() : PayslipExtras.none();
        String earningsHeading = extras.earningsHeading() != null && !extras.earningsHeading().isBlank() ? extras.earningsHeading() : "Earnings";
        String deductionsHeading = extras.deductionsHeading() != null && !extras.deductionsHeading().isBlank() ? extras.deductionsHeading() : "Deductions";
        List<Element> out = new ArrayList<>();

        if (extras.contributions().isEmpty()) {
            out.add(twoSectionTable(earningsHeading, m.earnings(), m.grossPay(), "Gross " + earningsHeading,
                    deductionsHeading, m.deductions(), m.totalDeductions(), "Total " + deductionsHeading,
                    extras.includeYtd(), m.currency()));
        } else {
            BigDecimal totalContributions = sum(extras.contributions());
            out.add(twoSectionTable(earningsHeading, m.earnings(), m.grossPay(), "Gross " + earningsHeading,
                    "Contributions", extras.contributions(), totalContributions, "Total Contributions",
                    extras.includeYtd(), m.currency()));
            out.add(spacer());
            out.add(oneSectionTable(deductionsHeading, m.deductions(), m.totalDeductions(), "Total " + deductionsHeading,
                    extras.includeYtd(), m.currency()));
        }
        return out;
    }

    private PdfPTable twoSectionTable(String leftHeading, List<Line> left, BigDecimal leftTotal, String leftTotalLabel,
                                       String rightHeading, List<Line> right, BigDecimal rightTotal, String rightTotalLabel,
                                       boolean includeYtd, String currency) {
        PdfPTable t = new PdfPTable(includeYtd ? new float[] {2.4f, 1.3f, 1f, 2.4f, 1.3f, 1f} : new float[] {3f, 1.5f, 3f, 1.5f});
        t.setWidthPercentage(100);
        header(t, leftHeading);
        header(t, "Amount (" + currency + ")");
        if (includeYtd) header(t, "YTD");
        header(t, rightHeading);
        header(t, "Amount (" + currency + ")");
        if (includeYtd) header(t, "YTD");

        int rows = Math.max(left.size(), right.size());
        for (int i = 0; i < rows; i++) {
            Line l = i < left.size() ? left.get(i) : null;
            Line r = i < right.size() ? right.get(i) : null;
            cell(t, l == null ? "" : l.name(), BODY, Element.ALIGN_LEFT);
            cell(t, l == null ? "" : money(l.amount()), BODY, Element.ALIGN_RIGHT);
            if (includeYtd) cell(t, l == null ? "" : money(l.ytd()), BODY, Element.ALIGN_RIGHT);
            cell(t, r == null ? "" : r.name(), BODY, Element.ALIGN_LEFT);
            cell(t, r == null ? "" : money(r.amount()), BODY, Element.ALIGN_RIGHT);
            if (includeYtd) cell(t, r == null ? "" : money(r.ytd()), BODY, Element.ALIGN_RIGHT);
        }
        cell(t, leftTotalLabel, BOLD, Element.ALIGN_LEFT);
        cell(t, money(leftTotal), BOLD, Element.ALIGN_RIGHT);
        if (includeYtd) cell(t, money(sumYtd(left)), BOLD, Element.ALIGN_RIGHT);
        cell(t, rightTotalLabel, BOLD, Element.ALIGN_LEFT);
        cell(t, money(rightTotal), BOLD, Element.ALIGN_RIGHT);
        if (includeYtd) cell(t, money(sumYtd(right)), BOLD, Element.ALIGN_RIGHT);
        return t;
    }

    private PdfPTable oneSectionTable(String heading, List<Line> lines, BigDecimal total, String totalLabel,
                                       boolean includeYtd, String currency) {
        PdfPTable t = new PdfPTable(includeYtd ? new float[] {4f, 2f, 1.5f} : new float[] {5f, 2f});
        t.setWidthPercentage(100);
        header(t, heading);
        header(t, "Amount (" + currency + ")");
        if (includeYtd) header(t, "YTD");
        for (Line l : lines) {
            cell(t, l.name(), BODY, Element.ALIGN_LEFT);
            cell(t, money(l.amount()), BODY, Element.ALIGN_RIGHT);
            if (includeYtd) cell(t, money(l.ytd()), BODY, Element.ALIGN_RIGHT);
        }
        cell(t, totalLabel, BOLD, Element.ALIGN_LEFT);
        cell(t, money(total), BOLD, Element.ALIGN_RIGHT);
        if (includeYtd) cell(t, money(sumYtd(lines)), BOLD, Element.ALIGN_RIGHT);
        return t;
    }

    private static BigDecimal sum(List<Line> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (Line l : lines) total = total.add(l.amount() == null ? BigDecimal.ZERO : l.amount());
        return total;
    }

    private static BigDecimal sumYtd(List<Line> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (Line l : lines) total = total.add(l.ytd() == null ? BigDecimal.ZERO : l.ytd());
        return total;
    }

    private PdfPTable leaveSummaryTable(PayslipExtras extras) {
        PdfPTable t = new PdfPTable(new float[] {5f, 2f});
        t.setWidthPercentage(100);
        header(t, "Leave Summary");
        header(t, "Balance");
        for (LeaveLine l : extras.leaveSummary()) {
            cell(t, l.leaveType(), BODY, Element.ALIGN_LEFT);
            cell(t, plain(l.balance()), BODY, Element.ALIGN_RIGHT);
        }
        return t;
    }

    private PdfPTable arrearBreakupTable(PayslipExtras extras) {
        PdfPTable t = new PdfPTable(new float[] {4f, 3f, 2f});
        t.setWidthPercentage(100);
        header(t, "Arrears Breakup");
        header(t, "Period");
        header(t, "Amount");
        for (ArrearLine a : extras.arrearBreakup()) {
            cell(t, a.reason(), BODY, Element.ALIGN_LEFT);
            cell(t, DATE.format(a.periodStart()) + " - " + DATE.format(a.periodEnd()), BODY, Element.ALIGN_LEFT);
            cell(t, money(a.amount()), BODY, Element.ALIGN_RIGHT);
        }
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

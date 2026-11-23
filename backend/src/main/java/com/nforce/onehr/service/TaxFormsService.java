package com.nforce.onehr.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * US-F13 — Form 130 (annual tax statement, PDF locked with the employee's PAN in capitals) and Form 124
 * (current declarations). Forms are rendered on demand from the same numbers the Income Tax tab shows, so
 * "publishing" Form 130 just flips the FY on and notifies everyone; no files are stored.
 */
@Service
@RequiredArgsConstructor
public class TaxFormsService {

    public record Form130Status(int fyStartYear, boolean published, LocalDate publishedOn, int employees, int ready, int missingPan) {}
    public record Pdf(byte[] data, String fileName) {}

    private final JdbcTemplate jdbc;
    private final IncomeTaxService incomeTaxService;
    private final TaxDeclarationService declarationService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public boolean isPublished(int fy) {
        return jdbc.queryForObject("SELECT count(*) FROM form130_publish WHERE fy_start_year = ?", Integer.class, fy) > 0;
    }

    /** Counts only; never exposes an individual employee's form. */
    @Transactional(readOnly = true)
    public Form130Status status(int fy) {
        List<LocalDate> pub = jdbc.queryForList("SELECT published_at::date FROM form130_publish WHERE fy_start_year = ?", LocalDate.class, fy);
        int total = jdbc.queryForObject("SELECT count(*) FROM employees", Integer.class);
        int missing = jdbc.queryForObject("SELECT count(*) FROM employees WHERE pan_number IS NULL OR btrim(pan_number) = ''", Integer.class);
        return new Form130Status(fy, !pub.isEmpty(), pub.isEmpty() ? null : pub.get(0), total, total - missing, missing);
    }

    @Transactional
    public Form130Status publish(int fy, UUID actor) {
        jdbc.update("INSERT INTO form130_publish (fy_start_year, published_by) VALUES (?, ?) ON CONFLICT (fy_start_year) DO UPDATE SET published_at = now(), published_by = EXCLUDED.published_by", fy, actor);
        auditService.log(actor, "FORM130_PUBLISHED", actor, null, "{\"fy\":" + fy + "}");
        for (UUID id : jdbc.queryForList("SELECT user_id FROM employees", UUID.class))
            notificationService.send(id, "TAX_FORM", "Form 130 available", "Your Form 130 for FY " + fy + "-" + String.valueOf(fy + 1).substring(2) + " is ready to download.", "/myfinances/tax/forms");
        return status(fy);
    }

    @Transactional(readOnly = true)
    public Pdf form130(UUID employeeId, int fy) {
        if (!isPublished(fy)) throw new IllegalStateException("Not yet available");
        String pan = jdbc.queryForList("SELECT pan_number FROM employees WHERE user_id = ?", String.class, employeeId).stream().findFirst().orElse(null);
        if (pan == null || pan.isBlank()) throw new IllegalStateException("Your PAN is missing, so Form 130 cannot be locked. Add it in Identity Information.");
        IncomeTaxService.Computation c = incomeTaxService.compute(employeeId, fy);
        return new Pdf(render("Form 130 - Statement of Tax Deducted at Source", c, employeeId, null, pan.trim().toUpperCase()), "Form130-" + c.fyLabel().replace(' ', '-') + ".pdf");
    }

    @Transactional(readOnly = true)
    public Pdf form124(UUID employeeId, int fy) {
        IncomeTaxService.Computation c = incomeTaxService.compute(employeeId, fy);
        return new Pdf(render("Form 124 - Investment Declaration", c, employeeId, declarationService.overview(employeeId, fy), null), "Form124-" + c.fyLabel().replace(' ', '-') + ".pdf");
    }

    private byte[] render(String title, IncomeTaxService.Computation c, UUID employeeId, TaxDeclarationService.Overview decl, String password) {
        String[] who = jdbc.query("SELECT full_name, employee_code, COALESCE(pan_number, '') FROM employees WHERE user_id = ?",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, employeeId).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter w = PdfWriter.getInstance(doc, out);
            if (password != null) w.setEncryption(password.getBytes(), (password + "-owner").getBytes(), PdfWriter.ALLOW_PRINTING, PdfWriter.ENCRYPTION_AES_128);
            doc.open();
            Font h = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14), b = FontFactory.getFont(FontFactory.HELVETICA, 10);
            doc.add(new Paragraph(title, h));
            doc.add(new Paragraph(c.fyLabel() + "  |  " + c.regime() + " regime", b));
            doc.add(new Paragraph(who[0] + " (" + who[1] + ")" + (who[2].isBlank() ? "" : "  |  PAN " + mask(who[2])), b));
            doc.add(new Paragraph(" "));
            PdfPTable t = new PdfPTable(new float[]{3, 1});
            t.setWidthPercentage(100);
            if (decl == null) {
                row(t, "Gross earnings", c.grossEarnings()); row(t, "Income from previous employer", c.previousEmployerIncome());
                row(t, "Other income declared", c.otherIncome()); row(t, "Standard deduction", c.standardDeduction());
                row(t, "Deductions (Chapter VI-A and exemptions)", c.deductions()); row(t, "Net taxable income", c.netTaxableIncome());
                row(t, "Tax payable (after rebate, surcharge, cess)", c.tax().netPayable()); row(t, "Tax deducted so far", c.taxPaidTillNow());
                row(t, "Of which by previous employer", c.previousEmployerTds());
            } else {
                for (TaxDeclarationService.Item i : decl.items()) row(t, i.category().replace('_', ' ') + " - " + i.name(), i.amount());
                row(t, "Total declared", decl.summary().declared()); row(t, "Accepted after proof", decl.summary().accepted());
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the form", e);
        } finally {
            if (doc.isOpen()) doc.close();
        }
        return out.toByteArray();
    }

    private static void row(PdfPTable t, String label, BigDecimal v) {
        Font f = FontFactory.getFont(FontFactory.HELVETICA, 10);
        t.addCell(new PdfPCell(new Paragraph(label, f)));
        PdfPCell c = new PdfPCell(new Paragraph("Rs " + (v == null ? "0" : v.setScale(0, RoundingMode.HALF_UP).toPlainString()), f));
        c.setHorizontalAlignment(PdfPCell.ALIGN_RIGHT);
        t.addCell(c);
    }

    private static String mask(String pan) { return pan.length() < 4 ? pan : "******" + pan.substring(pan.length() - 4); }
}

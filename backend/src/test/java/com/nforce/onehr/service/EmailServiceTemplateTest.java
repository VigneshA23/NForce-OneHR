package com.nforce.onehr.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Renders every email through the shared layout; nothing is sent. */
class EmailServiceTemplateTest {

    private final EmailService service = new EmailService();
    private final LocalDate date = LocalDate.of(2026, 11, 12);
    private final LocalTime in = LocalTime.of(9, 45);
    private final LocalTime out = LocalTime.of(18, 0);

    EmailServiceTemplateTest() {
        ReflectionTestUtils.setField(service, "baseUrl", "https://onehr.example.com");
    }

    private String render(String method, Object... args) {
        return ReflectionTestUtils.invokeMethod(service, method, args);
    }

    /** Subject-style title -> rendered HTML, one entry per email OneHR sends. */
    private Map<String, String> allEmails() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Welcome / invite", render("buildInviteHtml", "Aanuj Reddy", "aanuj@nforceone.com", "rZ$!bBcfZpSq"));
        m.put("Password reset", render("buildResetHtml", "Aanuj Reddy", "aanuj@nforceone.com", "rZ$!bBcfZpSq", "https://onehr.example.com"));
        m.put("Account email updated", render("buildEmailUpdatedHtml", "Aanuj Reddy", "new@nforceone.com", "old@nforceone.com"));
        m.put("Late arrival", render("buildLateArrivalHtml", "Aanuj Reddy", date, in, out, 45));
        m.put("Missing check-out", render("buildMissingPunchHtml", "Aanuj Reddy", date, in));
        m.put("Attendance during approved leave", render("buildLeaveAttendanceConflictHtml", "Aanuj Reddy", date, in));
        m.put("No attendance recorded", render("buildNoAttendanceHtml", "Aanuj Reddy", date));
        m.put("Work hours shortage", render("buildWorkHoursShortageHtml", "Aanuj Reddy", date, in, out));
        m.put("Work hours shortage (no check-out)", render("buildWorkHoursShortageHtml", "Aanuj Reddy", date, null, null));
        m.put("Attendance penalty", render("buildPenaltyHtml", "Aanuj Reddy", date, "Late arrival", BigDecimal.ONE, null, null));
        return m;
    }

    /** Opt-in: mvn test -Dtest=EmailServiceTemplateTest -Demail.preview=../docs/email-preview.html
     * (path relative to backend/) rewrites the all-emails preview page. */
    @Test
    @EnabledIfSystemProperty(named = "email.preview", matches = ".+")
    void writePreview() throws Exception {
        ReflectionTestUtils.setField(service, "baseUrl", "../frontend/public"); // so the file opens straight from docs/
        StringBuilder page = new StringBuilder("""
                <!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>OneHR email templates</title>
                <style>body{margin:0;padding:32px 16px;background:#E2E8F0;font-family:Arial,sans-serif}h1{text-align:center;color:#0F172A}
                h2{text-align:center;color:#475569;font-size:15px;margin:40px 0 10px}iframe{display:block;width:100%;max-width:640px;margin:0 auto;border:0;background:#F8FAFC;border-radius:12px}</style></head>
                <body><h1>NForce OneHR: every email</h1>
                """);
        allEmails().forEach((title, html) -> page.append("<h2>").append(HtmlUtils.htmlEscape(title)).append("</h2><iframe srcdoc=\"")
                .append(HtmlUtils.htmlEscape(html)).append("\" onload=\"this.style.height=this.contentDocument.body.scrollHeight+'px'\"></iframe>\n"));
        Files.writeString(Path.of(System.getProperty("email.preview")), page.append("</body></html>"));
    }

    @Test
    @DisplayName("every template renders with no leftover placeholders and the shared design")
    void allTemplatesRender() {
        List<String> pages = allEmails().values().stream().toList();
        for (String html : pages) {
            assertThat(html).contains("NForce OneHR", "https://onehr.example.com/email-header.jpg", "All rights reserved")
                    .doesNotContain("{{", "%s", "%%", "—");
        }
    }

    @Test
    @DisplayName("invite shows the password and links to the sign-in page; reset links to reset-password")
    void linksAndPassword() {
        String invite = render("buildInviteHtml", "Vignesh", "v@x.com", "rZ$!bBcfZpSq");
        assertThat(invite).contains("Welcome, Vignesh!", "rZ$!bBcfZpSq", "href=\"https://onehr.example.com/login\"");

        String reset = render("buildResetHtml", "Vignesh", "v@x.com", "pw", "https://onehr.example.com");
        assertThat(reset).contains("href=\"https://onehr.example.com/reset-password?email=v%40x.com\"");
    }

    @Test
    @DisplayName("a comma-separated app.base-url still yields one clean sign-in link")
    void listBaseUrlGivesOneLink() {
        assertThat(EmailService.pickBaseUrl("http://localhost:5173,https://onehr.nforceone.ai"))
                .isEqualTo("https://onehr.nforceone.ai");
        assertThat(EmailService.pickBaseUrl(" https://onehr.nforceone.ai/ ")).isEqualTo("https://onehr.nforceone.ai");
        assertThat(EmailService.pickBaseUrl("http://localhost:5180,http://localhost:5173")).isEqualTo("http://localhost:5180");
        assertThat(EmailService.pickBaseUrl("http://localhost:5180")).isEqualTo("http://localhost:5180");

        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:5173,https://onehr.nforceone.ai");
        ReflectionTestUtils.invokeMethod(service, "normalizeBaseUrl");
        assertThat((String) render("buildInviteHtml", "Nikhil", "n@x.com", "pw"))
                .contains("href=\"https://onehr.nforceone.ai/login\"").doesNotContain("localhost:5173,");
    }

    @Test
    @DisplayName("user-supplied text is HTML-escaped")
    void escapesInput() {
        String html = render("buildPenaltyHtml", "<b>Eve</b>", date, "<script>alert(1)</script>", BigDecimal.ONE, null, null);
        assertThat(html).doesNotContain("<script>", "<b>Eve</b>").contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("penalty lists only the deductions that apply")
    void penaltyRows() {
        String lop = render("buildPenaltyHtml", "A", date, "Late", null, new BigDecimal("0.5"), null);
        assertThat(lop).contains("Loss of Pay").contains("0.5 day(s)").doesNotContain("Leave balance deducted", "Deduction:");
        String plain = render("buildPenaltyHtml", "A", date, "Late", new BigDecimal("1"), null, null);
        assertThat(plain).contains("Deduction").contains("1 day(s)");
    }
}

package com.nforce.onehr.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

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

    @Test
    @DisplayName("every template renders with no leftover placeholders and the shared design")
    void allTemplatesRender() {
        List<String> pages = List.of(
                render("buildInviteHtml", "Vignesh", "v@x.com", "rZ$!bBcfZpSq"),
                render("buildEmailUpdatedHtml", "Vignesh", "new@x.com", "old@x.com"),
                render("buildResetHtml", "Vignesh", "v@x.com", "rZ$!bBcfZpSq", "https://onehr.example.com"),
                render("buildLateArrivalHtml", "Vignesh", date, in, out, 45),
                render("buildMissingPunchHtml", "Vignesh", date, in),
                render("buildLeaveAttendanceConflictHtml", "Vignesh", date, in),
                render("buildNoAttendanceHtml", "Vignesh", date),
                render("buildWorkHoursShortageHtml", "Vignesh", date, in, out),
                render("buildWorkHoursShortageHtml", "Vignesh", date, null, null),
                render("buildPenaltyHtml", "Vignesh", date, "Late", BigDecimal.ONE, null, null));
        for (String html : pages) {
            assertThat(html).contains("NForce OneHR", "Your people. One platform.", "All rights reserved", "#B11116")
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

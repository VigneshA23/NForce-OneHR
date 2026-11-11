package com.nforce.onehr.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class EmailService {

    private static final String RESEND_URL = "https://api.resend.com/emails";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a");

    /** Served as a static asset (frontend/public/nforce-logo.png, same source BrandMark.tsx uses)
     * and linked by URL rather than inlined as base64 — most mail clients (Gmail, Outlook) strip
     * or refuse to render data: URI images in the message body, so a fetchable URL is required
     * for the logo to actually show up in a delivered email. Requires the resolved base URL to be
     * reachable from wherever the recipient opens the email — a local dev base-url won't render
     * for real recipients outside that machine's network. */
    @Value("${resend.api-key}")
    private String apiKey;

    @Value("${resend.from}")
    private String fromAddress;

    @Value("${app.base-url:http://localhost:5180}")
    private String baseUrl;

    /** APP_BASE_URL is meant to be one URL, but is sometimes set to a comma-separated list like
     * CORS_ALLOWED_ORIGINS, which turns every emailed link into a broken "a,b/login" string. */
    @PostConstruct
    void normalizeBaseUrl() {
        String single = pickBaseUrl(baseUrl);
        if (!single.equals(baseUrl)) {
            log.warn("app.base-url holds more than one URL; emails will link to {}. Set APP_BASE_URL to a single URL.", single);
            baseUrl = single;
        }
    }

    /** One URL, no trailing slash: for a list, the first non-localhost entry (the deployed one),
     * else the first. */
    static String pickBaseUrl(String configured) {
        java.util.List<String> urls = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                .toList();
        return urls.stream().filter(u -> !u.contains("localhost") && !u.contains("127.0.0.1"))
                .findFirst().orElse(urls.isEmpty() ? configured : urls.get(0));
    }

    /** Same per-environment allowlist CORS is configured with — reused here so the password-reset
     * link can safely echo back whichever environment's origin actually made the request, instead
     * of always pointing at this instance's own default base URL. */
    @Value("${app.cors.allowed-origins:http://localhost:5180}")
    private String allowedOriginsConfig;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public void sendInviteEmail(String toEmail, String fullName, String tempPassword) {
        String subject = "Welcome to NForce OneHR: your account is ready";
        String html = buildInviteHtml(fullName, toEmail, tempPassword);
        sendAsync(toEmail, subject, html);
    }

    public void sendPasswordResetEmail(String toEmail, String fullName, String tempPassword, String requestOrigin) {
        String subject = "NForce OneHR: your password has been reset";
        String resolvedBaseUrl = resolveBaseUrl(requestOrigin);
        log.info("Password-reset email for {}: request Origin={}, resolved link base={}", toEmail, requestOrigin, resolvedBaseUrl);
        String html = buildResetHtml(fullName, toEmail, tempPassword, resolvedBaseUrl);
        sendAsync(toEmail, subject, html);
    }

    /** Sent to the NEW email address after a Super Admin edits a user's account email — confirms
     * the address is reachable and tells the user to sign in with it going forward. oldEmail is
     * shown for their own confidence that this wasn't a mistaken account. */
    public void sendEmailUpdatedEmail(String toEmail, String fullName, String oldEmail) {
        String subject = "NForce OneHR: your account email has been updated";
        String html = buildEmailUpdatedHtml(fullName, toEmail, oldEmail);
        sendAsync(toEmail, subject, html);
    }

    /** Echoes back the requesting environment's own origin (validated against the same
     * allowlist CORS uses) so the emailed link returns to wherever the request came from —
     * local, Dev, or any other deployed environment — instead of a fixed default. Falls back
     * to {@code app.base-url} when there's no Origin header or it isn't on the allowlist. */
    private String resolveBaseUrl(String requestOrigin) {
        if (requestOrigin == null || requestOrigin.isBlank()) {
            return baseUrl;
        }
        String normalizedOrigin = stripTrailingSlash(requestOrigin.trim());
        boolean allowed = java.util.Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .anyMatch(o -> stripTrailingSlash(o).equalsIgnoreCase(normalizedOrigin));
        return allowed ? normalizedOrigin : baseUrl;
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public CompletableFuture<Boolean> sendLateArrivalEmail(String toEmail, String ccEmail, String fullName, LocalDate date,
                                      LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
        String subject = "Late arrival recorded: " + DATE_FMT.format(date);
        String html = buildLateArrivalHtml(fullName, date, expectedTime, actualTime, minutesLate);
        return sendAsync(toEmail, ccEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public CompletableFuture<Boolean> sendMissingPunchEmail(String toEmail, String ccEmail, String fullName, LocalDate date, LocalTime checkInTime) {
        String subject = "Missing check-out: " + DATE_FMT.format(date);
        String html = buildMissingPunchHtml(fullName, date, checkInTime);
        return sendAsync(toEmail, ccEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public CompletableFuture<Boolean> sendLeaveAttendanceConflictEmail(String toEmail, String ccEmail, String fullName, LocalDate date, LocalTime checkInTime) {
        String subject = "Attendance recorded during approved leave: " + DATE_FMT.format(date);
        String html = buildLeaveAttendanceConflictHtml(fullName, date, checkInTime);
        return sendAsync(toEmail, ccEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public CompletableFuture<Boolean> sendNoAttendanceEmail(String toEmail, String ccEmail, String fullName, LocalDate date) {
        String subject = "No attendance recorded: " + DATE_FMT.format(date);
        String html = buildNoAttendanceHtml(fullName, date);
        return sendAsync(toEmail, ccEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file.
     * expectedTime/actualTime are null for the missing-checkout variant of this shortage (there is
     * no checkout to compare against — see ExceptionService#detectNoAttendanceAndShortage). */
    public CompletableFuture<Boolean> sendWorkHoursShortageEmail(String toEmail, String ccEmail, String fullName, LocalDate date,
                                            LocalTime expectedTime, LocalTime actualTime) {
        String subject = "Work hours shortage recorded: " + DATE_FMT.format(date);
        String html = buildWorkHoursShortageHtml(fullName, date, expectedTime, actualTime);
        return sendAsync(toEmail, ccEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file.
     * deductionDays/lopDays/leaveDeductionDays are whichever of the penalty's already-computed
     * amounts are non-null/non-zero — see AttendancePenaltyEvaluationService, the sole caller. */
    public void sendPenaltyEmail(String toEmail, String ccEmail, String fullName, LocalDate date, String reason,
                                  BigDecimal deductionDays, BigDecimal lopDays, BigDecimal leaveDeductionDays) {
        String subject = "Attendance Penalty Applied: " + DATE_FMT.format(date);
        String html = buildPenaltyHtml(fullName, date, reason, deductionDays, lopDays, leaveDeductionDays);
        sendAsync(toEmail, ccEmail, subject, html);
    }

    private void sendAsync(String to, String subject, String html) {
        sendAsync(to, null, subject, html);
    }

    /** Completes with {@code true} only when Resend accepted the message (2xx); {@code false} on any
     * refusal, timeout or transport error — never completes exceptionally, so a caller that needs to
     * know whether to retry (see ExceptionService#notifyUnnotifiedExceptions) can simply join(). */
    private CompletableFuture<Boolean> sendAsync(String to, String cc, String subject, String html) {
        String ccField = cc == null ? "" : """
                ,
                  "cc": ["%s"]""".formatted(cc);
        String body = """
                {
                  "from": "%s",
                  "to": ["%s"]%s,
                  "subject": "%s",
                  "html": %s
                }
                """.formatted(fromAddress, to, ccField, subject, jsonString(html));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RESEND_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    if (res.statusCode() >= 200 && res.statusCode() < 300) {
                        log.info("Email sent to {} cc {} (subject: {})", to, cc, subject);
                        return true;
                    }
                    log.error("Resend API error: status={} body={}", res.statusCode(), res.body());
                    return false;
                })
                .exceptionally(ex -> {
                    log.error("Failed to send email to {} — underlying action was not affected: {}", to, ex.getMessage());
                    return false;
                });
    }

    // ── HTML templates ───────────────────────────────────────────────────────────────────────
    // One shared layout (header, white card, optional button, help note, footer); each email only
    // supplies its heading, intro and content. Variables are HTML-escaped via esc() at the call
    // site, so the helpers below take ready-made HTML.

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }

    private String layout(String heading, String intro, String content, String buttonLabel, String buttonUrl, String note) {
        String button = buttonUrl == null ? "" : """
                <table cellpadding="0" cellspacing="0" role="presentation" align="center" style="margin:0 auto 32px;">
                  <tr><td align="center" style="background:#B11116;border-radius:10px;">
                    <a href="{{url}}" style="display:inline-block;font-family:Inter,Arial,sans-serif;font-size:16px;font-weight:700;color:#ffffff;text-decoration:none;padding:15px 40px;">{{label}}</a>
                  </td></tr>
                </table>
                """.replace("{{url}}", buttonUrl).replace("{{label}}", buttonLabel);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#F1F5F9;font-family:Inter,Arial,sans-serif;">
                  <table width="100%" cellpadding="0" cellspacing="0" role="presentation" style="background:#F1F5F9;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;max-width:560px;background:#ffffff;border-radius:16px;overflow:hidden;">
                        <tr><td align="center" style="background:#B11116;padding:34px 36px 30px;">
                          <div style="font-family:'Space Grotesk',Arial,sans-serif;font-size:24px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;line-height:1.2;">NForce OneHR</div>
                          <div style="font-family:Inter,Arial,sans-serif;font-size:14px;color:#F3C4C6;margin-top:6px;">Your people. One platform.</div>
                        </td></tr>
                        <tr><td style="padding:40px 40px 32px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:26px;font-weight:700;color:#111827;margin:0 0 10px;letter-spacing:-0.01em;">{{heading}}</h1>
                          <p style="font-size:15px;line-height:1.75;color:#6B7280;margin:0 0 32px;">{{intro}}</p>
                          {{content}}
                          {{button}}
                          <p style="font-size:13.5px;line-height:1.7;color:#94A3B8;margin:0;">{{note}}</p>
                        </td></tr>
                        <tr><td align="center" style="background:#F8FAFC;border-top:1px solid #E2E8F0;padding:22px 36px;">
                          <p style="margin:0;font-size:13px;color:#94A3B8;">&copy; {{year}} NForce OneHR &middot; All rights reserved</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """
                .replace("{{heading}}", heading)
                .replace("{{intro}}", intro)
                .replace("{{content}}", content)
                .replace("{{button}}", button)
                .replace("{{note}}", note)
                .replace("{{year}}", String.valueOf(java.time.Year.now().getValue()));
    }

    /** Label/value rows in a soft card. Arguments are alternating label, value; both already escaped. */
    private static String details(String... labelValuePairs) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < labelValuePairs.length; i += 2) {
            rows.append("<p style=\"margin:0 0 8px;font-size:14px;color:#6B7280;\">").append(labelValuePairs[i])
                    .append(": <span style=\"color:#111827;font-weight:600;\">").append(labelValuePairs[i + 1])
                    .append("</span></p>");
        }
        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" role=\"presentation\" style=\"background:#F8FAFC;"
                + "border:1px solid #E2E8F0;border-radius:14px;margin-bottom:32px;\"><tr><td style=\"padding:22px 24px 14px;\">"
                + rows + "</td></tr></table>";
    }

    /** Tinted notice. warn = amber, otherwise red. */
    private static String callout(String text, boolean warn) {
        String bg = warn ? "#FFFBEB" : "#FEF2F2";
        String border = warn ? "#FDE68A" : "#FECACA";
        String color = warn ? "#92400E" : "#991B1B";
        return "<div style=\"background:" + bg + ";border:1px solid " + border + ";border-radius:12px;padding:16px 20px;margin-bottom:32px;\">"
                + "<p style=\"margin:0;font-size:14px;color:" + color + ";line-height:1.6;\">" + text + "</p></div>";
    }

    private static String passwordBox(String tempPassword) {
        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" role=\"presentation\" style=\"background:#F8FAFC;"
                + "border:2px dashed #CBD5E1;border-radius:14px;margin-bottom:32px;\"><tr><td align=\"center\" style=\"padding:28px 16px;\">"
                + "<div style=\"font-size:12px;font-weight:700;color:#94A3B8;text-transform:uppercase;letter-spacing:.16em;margin-bottom:14px;\">Temporary password</div>"
                + "<div style=\"font-family:'Courier New',Courier,monospace;font-size:28px;font-weight:700;color:#111827;letter-spacing:.14em;word-break:break-all;\">"
                + esc(tempPassword) + "</div></td></tr></table>";
    }

    private static final String AUTOMATED_NOTE = "This is an automated notice. If you believe this is incorrect, "
            + "contact your manager or HR administrator.";

    private String buildInviteHtml(String fullName, String email, String tempPassword) {
        return layout("Welcome, " + esc(fullName) + "!",
                "Your NForce OneHR account is ready. Use the temporary password below to sign in with <strong style=\"color:#374151;font-weight:600;\">"
                        + esc(email) + "</strong>. You'll be asked to create a new one on your first login.",
                passwordBox(tempPassword),
                "Sign in to OneHR", baseUrl + "/login",
                "Need help? Contact your manager or your HR administrator.<br>This is an automated message, please do not reply.");
    }

    private String buildEmailUpdatedHtml(String fullName, String newEmail, String oldEmail) {
        return layout("Your account email was updated",
                "Hi " + esc(fullName) + ", an administrator has updated the email address on your NForce OneHR account.",
                details("Previous email", esc(oldEmail), "New email", esc(newEmail))
                        + callout("Sign in using this new email address from now on.", false),
                "Sign in to OneHR", baseUrl + "/login",
                "If you didn't expect this change, contact your HR administrator immediately.<br>This is an automated message, please do not reply.");
    }

    private String buildResetHtml(String fullName, String email, String tempPassword, String baseUrl) {
        // Carries the email as a query param so the linked page (ResetPasswordPage in the
        // frontend) never has to ask the user to type it again — see forgot-password flow.
        String resetLink = baseUrl + "/reset-password?email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8);
        return layout("Reset your password",
                "Hi " + esc(fullName) + ", a password reset was requested for <strong style=\"color:#374151;font-weight:600;\">"
                        + esc(email) + "</strong>. Use the temporary password below to sign in.",
                passwordBox(tempPassword)
                        + callout("This password is for one-time use only. Enter it on the next page along with your new password to finish resetting your account.", true),
                "Reset your password", resetLink,
                "If you didn't request this reset, contact your HR administrator immediately.<br>This is an automated message, please do not reply.");
    }

    private String buildLateArrivalHtml(String fullName, LocalDate date, LocalTime expectedTime,
                                         LocalTime actualTime, Integer minutesLate) {
        return layout("Late arrival recorded",
                "Hi " + esc(fullName) + ", your check-in on " + DATE_FMT.format(date) + " was recorded after the shift start grace period.",
                details("Expected", TIME_FMT.format(expectedTime), "Actual check-in", TIME_FMT.format(actualTime),
                        "Minutes late", String.valueOf(minutesLate)),
                null, null, AUTOMATED_NOTE);
    }

    private String buildMissingPunchHtml(String fullName, LocalDate date, LocalTime checkInTime) {
        return layout("Missing check-out",
                "Hi " + esc(fullName) + ", we noticed you checked in on " + DATE_FMT.format(date) + " at "
                        + TIME_FMT.format(checkInTime) + " but never checked out.",
                callout("Please submit an Attendance Regularization request with your correct check-out time so your manager or HR can review it.", true),
                null, null, AUTOMATED_NOTE);
    }

    private String buildLeaveAttendanceConflictHtml(String fullName, LocalDate date, LocalTime checkInTime) {
        return layout("Attendance recorded during approved leave",
                "Hi " + esc(fullName) + ", you have an approved leave covering " + DATE_FMT.format(date)
                        + ", but a check-in at " + TIME_FMT.format(checkInTime) + " was also recorded that day.",
                callout("If this was unintentional, contact your manager or HR administrator to correct your attendance or leave record.", false),
                null, null, AUTOMATED_NOTE);
    }

    private String buildNoAttendanceHtml(String fullName, LocalDate date) {
        return layout("No attendance recorded",
                "Hi " + esc(fullName) + ", we did not receive any attendance record from you on " + DATE_FMT.format(date)
                        + ", a day you were expected to work.",
                callout("If you were on approved leave or this is otherwise incorrect, submit an Attendance Regularization request or contact your manager or HR administrator.", false),
                null, null, "This is an automated notice.");
    }

    private String buildWorkHoursShortageHtml(String fullName, LocalDate date, LocalTime expectedTime, LocalTime actualTime) {
        String detailRow = expectedTime != null && actualTime != null
                ? details("Expected check-out", TIME_FMT.format(expectedTime), "Actual check-out", TIME_FMT.format(actualTime))
                : "";
        return layout("Work hours shortage recorded",
                "Hi " + esc(fullName) + ", your worked hours on " + DATE_FMT.format(date) + " were short of what was expected for that day.",
                detailRow, null, null, AUTOMATED_NOTE);
    }

    private String buildPenaltyHtml(String fullName, LocalDate date, String reason,
                                     BigDecimal deductionDays, BigDecimal lopDays, BigDecimal leaveDeductionDays) {
        java.util.List<String> rows = new java.util.ArrayList<>(java.util.List.of(
                "Penalty date", DATE_FMT.format(date), "Reason", esc(reason)));
        if (lopDays != null && lopDays.signum() > 0) {
            rows.addAll(java.util.List.of("Loss of Pay", lopDays + " day(s)"));
        }
        if (leaveDeductionDays != null && leaveDeductionDays.signum() > 0) {
            rows.addAll(java.util.List.of("Leave balance deducted", leaveDeductionDays + " day(s)"));
        }
        if (rows.size() == 4) { // neither of the above applied
            rows.addAll(java.util.List.of("Deduction", (deductionDays != null ? deductionDays : BigDecimal.ZERO) + " day(s)"));
        }
        return layout("Attendance penalty applied",
                "Hi " + esc(fullName) + ", an attendance penalty has been applied to your record for " + DATE_FMT.format(date) + ".",
                details(rows.toArray(String[]::new)),
                null, null,
                "If you believe this is incorrect, you can submit an Attendance Regularization request for this date, or contact your manager or HR administrator.");
    }

    private String jsonString(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}

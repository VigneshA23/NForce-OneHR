package com.nforce.onehr.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
public class EmailService {

    private static final String RESEND_URL = "https://api.resend.com/emails";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a");

    @Value("${resend.api-key}")
    private String apiKey;

    @Value("${resend.from}")
    private String fromAddress;

    @Value("${app.base-url:http://localhost:5180}")
    private String baseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public void sendInviteEmail(String toEmail, String fullName, String tempPassword) {
        String subject = "Welcome to NForce OneHR — your account is ready";
        String html = buildInviteHtml(fullName, toEmail, tempPassword);
        sendAsync(toEmail, subject, html);
    }

    public void sendPasswordResetEmail(String toEmail, String fullName, String tempPassword) {
        String subject = "NForce OneHR — your password has been reset";
        String html = buildResetHtml(fullName, toEmail, tempPassword);
        sendAsync(toEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public void sendLateArrivalEmail(String toEmail, String ccEmail, String fullName, LocalDate date,
                                      LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
        String subject = "Late arrival recorded — " + DATE_FMT.format(date);
        String html = buildLateArrivalHtml(fullName, date, expectedTime, actualTime, minutesLate);
        sendAsync(toEmail, ccEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public void sendMissingPunchEmail(String toEmail, String ccEmail, String fullName, LocalDate date, LocalTime checkInTime) {
        String subject = "Missing check-out — " + DATE_FMT.format(date);
        String html = buildMissingPunchHtml(fullName, date, checkInTime);
        sendAsync(toEmail, ccEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public void sendLeaveAttendanceConflictEmail(String toEmail, String ccEmail, String fullName, LocalDate date, LocalTime checkInTime) {
        String subject = "Attendance recorded during approved leave — " + DATE_FMT.format(date);
        String html = buildLeaveAttendanceConflictHtml(fullName, date, checkInTime);
        sendAsync(toEmail, ccEmail, subject, html);
    }

    private void sendAsync(String to, String subject, String html) {
        sendAsync(to, null, subject, html);
    }

    private void sendAsync(String to, String cc, String subject, String html) {
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

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() >= 200 && res.statusCode() < 300) {
                        log.info("Email sent to {} cc {} (subject: {})", to, cc, subject);
                    } else {
                        log.error("Resend API error: status={} body={}", res.statusCode(), res.body());
                    }
                })
                .exceptionally(ex -> {
                    log.error("Failed to send email to {} — underlying action was not affected: {}", to, ex.getMessage());
                    return null;
                });
    }

    private String buildInviteHtml(String fullName, String email, String tempPassword) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#080808;font-family:Inter,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080808;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#16181D;border:1px solid #2A2E37;border-radius:12px;overflow:hidden;">
                        <!-- Header -->
                        <tr><td style="background:#B11116;padding:28px 36px;">
                          <span style="font-family:'Space Grotesk',Arial,sans-serif;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">NForce OneHR</span>
                        </td></tr>
                        <!-- Body -->
                        <tr><td style="padding:36px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:22px;font-weight:700;color:#E8EAED;margin:0 0 8px;">Your account is ready</h1>
                          <p style="color:#9BA1AC;font-size:14px;line-height:1.6;margin:0 0 28px;">Hi %s, welcome to NForce OneHR. Your account has been created and you can log in below.</p>

                          <table width="100%%" cellpadding="0" cellspacing="0" style="background:#1E2128;border:1px solid #2A2E37;border-radius:8px;margin-bottom:28px;">
                            <tr><td style="padding:20px 24px;">
                              <p style="margin:0 0 10px;font-size:12px;font-weight:700;color:#6B7280;text-transform:uppercase;letter-spacing:.06em;">Your login credentials</p>
                              <p style="margin:0 0 6px;font-size:13px;color:#9BA1AC;">Email: <span style="color:#E8EAED;font-weight:600;">%s</span></p>
                              <p style="margin:0;font-size:13px;color:#9BA1AC;">Temp password: <span style="font-family:'Courier New',monospace;background:#080808;color:#E8EAED;padding:3px 8px;border-radius:4px;font-size:14px;font-weight:600;">%s</span></p>
                            </td></tr>
                          </table>

                          <div style="background:rgba(228,55,61,.08);border:1px solid rgba(228,55,61,.2);border-radius:8px;padding:14px 18px;margin-bottom:28px;">
                            <p style="margin:0;font-size:13px;color:#f4a5a8;line-height:1.5;">You will be required to set your own password on first login. Keep this email private.</p>
                          </div>

                          <a href="%s/login" style="display:inline-block;background:#B11116;color:#ffffff;font-weight:700;font-size:14px;text-decoration:none;padding:12px 28px;border-radius:8px;">Sign in to OneHR →</a>
                        </td></tr>
                        <!-- Footer -->
                        <tr><td style="padding:20px 36px;border-top:1px solid #2A2E37;">
                          <p style="margin:0;font-size:12px;color:#6B7280;">This email was sent by NForce OneHR. If you didn't expect this, contact your HR administrator.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(fullName, email, tempPassword, baseUrl);
    }

    private String buildResetHtml(String fullName, String email, String tempPassword) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#080808;font-family:Inter,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080808;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#16181D;border:1px solid #2A2E37;border-radius:12px;overflow:hidden;">
                        <tr><td style="background:#B11116;padding:28px 36px;">
                          <span style="font-family:'Space Grotesk',Arial,sans-serif;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">NForce OneHR</span>
                        </td></tr>
                        <tr><td style="padding:36px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:22px;font-weight:700;color:#E8EAED;margin:0 0 8px;">Reset your password</h1>
                          <p style="color:#9BA1AC;font-size:14px;line-height:1.6;margin:0 0 28px;">Hi %s, a password reset was requested for your NForce OneHR account. Use the temporary password below to sign in.</p>

                          <table width="100%%" cellpadding="0" cellspacing="0" style="background:#1E2128;border:1px solid #2A2E37;border-radius:8px;margin-bottom:28px;">
                            <tr><td style="padding:20px 24px;">
                              <p style="margin:0 0 10px;font-size:12px;font-weight:700;color:#6B7280;text-transform:uppercase;letter-spacing:.06em;">New temporary password</p>
                              <p style="margin:0 0 6px;font-size:13px;color:#9BA1AC;">Email: <span style="color:#E8EAED;font-weight:600;">%s</span></p>
                              <p style="margin:0;font-size:13px;color:#9BA1AC;">Temp password: <span style="font-family:'Courier New',monospace;background:#080808;color:#E8EAED;padding:3px 8px;border-radius:4px;font-size:14px;font-weight:600;">%s</span></p>
                            </td></tr>
                          </table>

                          <div style="background:rgba(228,55,61,.08);border:1px solid rgba(228,55,61,.2);border-radius:8px;padding:14px 18px;margin-bottom:28px;">
                            <p style="margin:0;font-size:13px;color:#f4a5a8;line-height:1.5;">You will be required to set a new password on sign-in. If you didn't request this, contact your HR administrator immediately.</p>
                          </div>

                          <a href="%s/login" style="display:inline-block;background:#B11116;color:#ffffff;font-weight:700;font-size:14px;text-decoration:none;padding:12px 28px;border-radius:8px;">Sign in to OneHR →</a>
                        </td></tr>
                        <tr><td style="padding:20px 36px;border-top:1px solid #2A2E37;">
                          <p style="margin:0;font-size:12px;color:#6B7280;">This email was sent by NForce OneHR. If you didn't request a password reset, please ignore this email.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(fullName, email, tempPassword, baseUrl);
    }

    private String buildLateArrivalHtml(String fullName, LocalDate date, LocalTime expectedTime,
                                         LocalTime actualTime, Integer minutesLate) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#080808;font-family:Inter,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080808;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#16181D;border:1px solid #2A2E37;border-radius:12px;overflow:hidden;">
                        <tr><td style="background:#B11116;padding:28px 36px;">
                          <span style="font-family:'Space Grotesk',Arial,sans-serif;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">NForce OneHR</span>
                        </td></tr>
                        <tr><td style="padding:36px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:22px;font-weight:700;color:#E8EAED;margin:0 0 8px;">Late arrival recorded</h1>
                          <p style="color:#9BA1AC;font-size:14px;line-height:1.6;margin:0 0 28px;">Hi %s, your check-in on %s was recorded after the shift start grace period.</p>

                          <table width="100%%" cellpadding="0" cellspacing="0" style="background:#1E2128;border:1px solid #2A2E37;border-radius:8px;margin-bottom:28px;">
                            <tr><td style="padding:20px 24px;">
                              <p style="margin:0 0 6px;font-size:13px;color:#9BA1AC;">Expected: <span style="color:#E8EAED;font-weight:600;">%s</span></p>
                              <p style="margin:0 0 6px;font-size:13px;color:#9BA1AC;">Actual check-in: <span style="color:#E8EAED;font-weight:600;">%s</span></p>
                              <p style="margin:0;font-size:13px;color:#9BA1AC;">Minutes late: <span style="color:#E8EAED;font-weight:600;">%d</span></p>
                            </td></tr>
                          </table>

                          <p style="color:#6B7280;font-size:12px;line-height:1.5;margin:0;">This is an automated notice. If you believe this is incorrect, contact your manager or HR administrator.</p>
                        </td></tr>
                        <tr><td style="padding:20px 36px;border-top:1px solid #2A2E37;">
                          <p style="margin:0;font-size:12px;color:#6B7280;">This email was sent by NForce OneHR.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(fullName, DATE_FMT.format(date),
                        TIME_FMT.format(expectedTime), TIME_FMT.format(actualTime), minutesLate);
    }

    private String buildMissingPunchHtml(String fullName, LocalDate date, LocalTime checkInTime) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#080808;font-family:Inter,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080808;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#16181D;border:1px solid #2A2E37;border-radius:12px;overflow:hidden;">
                        <tr><td style="background:#B11116;padding:28px 36px;">
                          <span style="font-family:'Space Grotesk',Arial,sans-serif;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">NForce OneHR</span>
                        </td></tr>
                        <tr><td style="padding:36px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:22px;font-weight:700;color:#E8EAED;margin:0 0 8px;">Missing check-out</h1>
                          <p style="color:#9BA1AC;font-size:14px;line-height:1.6;margin:0 0 28px;">Hi %s, we noticed you checked in on %s at %s but never checked out.</p>

                          <div style="background:rgba(224,169,59,.08);border:1px solid rgba(224,169,59,.2);border-radius:8px;padding:14px 18px;margin-bottom:28px;">
                            <p style="margin:0;font-size:13px;color:#E0A93B;line-height:1.5;">Please submit an Attendance Regularization request with your correct check-out time so your manager or HR can review it.</p>
                          </div>

                          <p style="color:#6B7280;font-size:12px;line-height:1.5;margin:0;">This is an automated notice. If you believe this is incorrect, contact your manager or HR administrator.</p>
                        </td></tr>
                        <tr><td style="padding:20px 36px;border-top:1px solid #2A2E37;">
                          <p style="margin:0;font-size:12px;color:#6B7280;">This email was sent by NForce OneHR.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(fullName, DATE_FMT.format(date), TIME_FMT.format(checkInTime));
    }

    private String buildLeaveAttendanceConflictHtml(String fullName, LocalDate date, LocalTime checkInTime) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#080808;font-family:Inter,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080808;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#16181D;border:1px solid #2A2E37;border-radius:12px;overflow:hidden;">
                        <tr><td style="background:#B11116;padding:28px 36px;">
                          <span style="font-family:'Space Grotesk',Arial,sans-serif;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">NForce OneHR</span>
                        </td></tr>
                        <tr><td style="padding:36px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:22px;font-weight:700;color:#E8EAED;margin:0 0 8px;">Attendance recorded during approved leave</h1>
                          <p style="color:#9BA1AC;font-size:14px;line-height:1.6;margin:0 0 28px;">Hi %s, you have an approved leave covering %s, but a check-in at %s was also recorded that day.</p>

                          <div style="background:rgba(228,55,61,.08);border:1px solid rgba(228,55,61,.2);border-radius:8px;padding:14px 18px;margin-bottom:28px;">
                            <p style="margin:0;font-size:13px;color:#f4a5a8;line-height:1.5;">If this was unintentional, contact your manager or HR administrator to correct your attendance or leave record.</p>
                          </div>

                          <p style="color:#6B7280;font-size:12px;line-height:1.5;margin:0;">This is an automated notice. If you believe this is incorrect, contact your manager or HR administrator.</p>
                        </td></tr>
                        <tr><td style="padding:20px 36px;border-top:1px solid #2A2E37;">
                          <p style="margin:0;font-size:12px;color:#6B7280;">This email was sent by NForce OneHR.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(fullName, DATE_FMT.format(date), TIME_FMT.format(checkInTime));
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

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.reports.AttendanceRequestReportRow;
import com.nforce.onehr.service.ReportsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/** Manager: Attendance Request Reports Library (ONEHR-109) — the 4 real, data-backed report cards. */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportsController {

    private final ReportsService reportsService;

    @GetMapping("/attendance-requests")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<AttendanceRequestReportRow> attendanceRequests(
            @RequestParam ReportsService.ReportType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return reportsService.getAttendanceRequestReport(principal.getName(), type, from, to);
    }

    @GetMapping("/attendance-requests/export")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> exportAttendanceRequests(
            @RequestParam ReportsService.ReportType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        List<AttendanceRequestReportRow> rows = reportsService.getAttendanceRequestReport(principal.getName(), type, from, to);
        byte[] csv = reportsService.toCsv(rows).getBytes(StandardCharsets.UTF_8);
        String filename = type.name().toLowerCase() + "-report-" + from + "-to-" + to + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}

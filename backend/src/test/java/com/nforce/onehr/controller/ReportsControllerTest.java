package com.nforce.onehr.controller;

import com.nforce.onehr.dto.reports.AttendanceRequestReportRow;
import com.nforce.onehr.service.ReportsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit tests (no MockMvc — trusts Spring's own {@code @PreAuthorize} enforcement,
 * same convention as AuditLogControllerTest/AttendanceControllerTest). Focus: the manager
 * identity that scopes the report always comes from the authenticated Principal, never from a
 * request parameter the client could substitute to reach another manager's team.
 */
@ExtendWith(MockitoExtension.class)
class ReportsControllerTest {

    @Mock private ReportsService reportsService;
    @Mock private Principal principal;

    private ReportsController controller;

    private final LocalDate from = LocalDate.of(2026, 9, 1);
    private final LocalDate to = LocalDate.of(2026, 9, 16);

    @BeforeEach
    void setUp() {
        controller = new ReportsController(reportsService);
        lenient().when(principal.getName()).thenReturn("manager@test.com");
    }

    @Test
    void attendanceRequests_delegatesWithPrincipalEmail_notAClientSuppliedId() {
        List<AttendanceRequestReportRow> rows = List.of(AttendanceRequestReportRow.builder().status("PENDING").build());
        when(reportsService.getAttendanceRequestReport("manager@test.com", ReportsService.ReportType.REGULARIZATION, from, to))
                .thenReturn(rows);

        List<AttendanceRequestReportRow> result = controller.attendanceRequests(ReportsService.ReportType.REGULARIZATION, from, to, principal);

        assertEquals(rows, result);
        verify(reportsService).getAttendanceRequestReport("manager@test.com", ReportsService.ReportType.REGULARIZATION, from, to);
    }

    @Test
    void exportAttendanceRequests_returnsCsvWithSafeFilename_noInternalIds() {
        List<AttendanceRequestReportRow> rows = List.of(
                AttendanceRequestReportRow.builder().employeeCode("NF-1").fullName("Employee One").status("APPROVED").build());
        when(reportsService.getAttendanceRequestReport("manager@test.com", ReportsService.ReportType.WEB_CLOCK_IN, from, to))
                .thenReturn(rows);
        when(reportsService.toCsv(rows)).thenReturn("Employee Code,Employee Name,Status\nNF-1,Employee One,APPROVED\n");

        ResponseEntity<byte[]> response = controller.exportAttendanceRequests(ReportsService.ReportType.WEB_CLOCK_IN, from, to, principal);

        assertEquals(200, response.getStatusCode().value());
        String contentDisposition = response.getHeaders().getFirst("Content-Disposition");
        assertNotNull(contentDisposition);
        assertTrue(contentDisposition.contains("web_clock_in-report-2026-09-01-to-2026-09-16.csv"));
        assertFalse(contentDisposition.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*"),
                "filename must never leak an internal UUID");
    }
}

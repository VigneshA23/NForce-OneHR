package com.nforce.onehr.controller;

import com.nforce.onehr.dto.MyRequestItemDto;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.dto.attendance.WebClockInResponse;
import com.nforce.onehr.service.LeaveService;
import com.nforce.onehr.service.RegularizationService;
import com.nforce.onehr.service.WebClockInService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Requester-side mirror of the Unified Approval Center: aggregates the caller's
 * own Leave, Attendance Regularization, and Web Clock-In submissions into a
 * single read-only tracking list. Issues no decisions — approve/reject for
 * every request type happens exclusively via Approval Center.
 */
@RestController
@RequestMapping("/api/my-requests")
@RequiredArgsConstructor
public class MyRequestsController {

    private final LeaveService leaveService;
    private final RegularizationService regularizationService;
    private final WebClockInService webClockInService;

    @GetMapping
    public List<MyRequestItemDto> myRequests(Principal principal) {
        String email = principal.getName();

        List<MyRequestItemDto> items = new ArrayList<>();
        leaveService.listMyRequests(email).stream().map(this::leaveToItem).forEach(items::add);
        regularizationService.listMine(email).stream().map(this::regularizationToItem).forEach(items::add);
        webClockInService.listMine(email).stream().map(this::webClockInToItem).forEach(items::add);

        items.sort(Comparator.comparing(MyRequestItemDto::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    // ── Mappers ───────────────────────────────────────────

    private MyRequestItemDto leaveToItem(LeaveRequestResponse r) {
        return MyRequestItemDto.builder()
                .id(r.getId().toString())
                .requestType("LEAVE")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .status(r.getStatus())
                .decisionReason(r.getDecisionReason())
                .decidedByName(r.getDecidedByName())
                .decidedAt(r.getDecidedAt() != null
                        ? r.getDecidedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .leaveTypeName(r.getLeaveTypeName())
                .leaveStartDate(r.getStartDate())
                .leaveEndDate(r.getEndDate())
                .leaveTotalDays(r.getTotalDays())
                .leaveHalfDay(r.isHalfDay())
                .leaveReason(r.getEmployeeReason())
                .build();
    }

    private MyRequestItemDto regularizationToItem(RegularizationResponse r) {
        return MyRequestItemDto.builder()
                .id(r.getId().toString())
                .requestType("REGULARIZATION")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .status(r.getStatus())
                .decisionReason(r.getReviewComment())
                .decidedByName(r.getReviewedByName())
                .decidedAt(r.getReviewedAt() != null
                        ? r.getReviewedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getAttendanceDate())
                .requestedCheckIn(r.getRequestedCheckIn())
                .requestedCheckOut(r.getRequestedCheckOut())
                .regularizationReason(r.getReason())
                .build();
    }

    private MyRequestItemDto webClockInToItem(WebClockInResponse r) {
        return MyRequestItemDto.builder()
                .id(r.getId().toString())
                .requestType("WEB_CLOCK_IN")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .status(r.getStatus())
                .decisionReason(r.getReviewComment())
                .decidedByName(r.getReviewedByName())
                .decidedAt(r.getReviewedAt() != null
                        ? r.getReviewedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getWorkDate())
                .requestedCheckIn(r.getRequestedCheckIn())
                // Reused field: for WEB_CLOCK_IN this carries the actual check-out time
                // (set via the no-approval Web Clock Out action), not a "requested" one.
                .requestedCheckOut(r.getCheckedOutAt())
                .regularizationReason(r.getReason())
                .build();
    }
}

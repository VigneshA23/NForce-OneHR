package com.nforce.onehr.controller;

import com.nforce.onehr.dto.attendance.BulkApproveRegularizationRequest;
import com.nforce.onehr.dto.attendance.BulkRegularizationResultResponse;
import com.nforce.onehr.dto.attendance.BulkRejectRegularizationRequest;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.service.AttendanceService;
import com.nforce.onehr.service.AttendanceStatsService;
import com.nforce.onehr.service.RegularizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests for the bulk regularization endpoints — same isolation approach as
 * RegularizationServiceTest (no Spring ApplicationContext, no MockMvc). Focus is the
 * per-item independence contract: one id's failure must not affect the others.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceControllerTest {

    @Mock private AttendanceService attendanceService;
    @Mock private AttendanceStatsService attendanceStatsService;
    @Mock private RegularizationService regularizationService;
    @Mock private Principal principal;

    private AttendanceController controller;

    @BeforeEach
    void setUp() {
        controller = new AttendanceController(attendanceService, attendanceStatsService, regularizationService);
        lenient().when(principal.getName()).thenReturn("manager@test.com");
    }

    @Test
    void bulkApprove_partialFailure_reportsSucceededAndFailedIndependently() {
        UUID okId = UUID.randomUUID();
        UUID failId = UUID.randomUUID();
        when(regularizationService.approve(eq(okId), any(), eq("manager@test.com")))
                .thenReturn(RegularizationResponse.builder().id(okId).status("PARTIALLY_APPROVED").build());
        when(regularizationService.approve(eq(failId), any(), eq("manager@test.com")))
                .thenThrow(new IllegalArgumentException("Only pending or partially-approved requests can be approved"));

        BulkRegularizationResultResponse result = controller.bulkApprove(
                BulkApproveRegularizationRequest.builder().ids(List.of(okId, failId)).build(), principal);

        assertEquals(List.of(okId), result.getSucceededIds());
        assertEquals(1, result.getFailed().size());
        assertEquals(failId, result.getFailed().get(0).getId());
        // Both ids must have been attempted — one failure must not short-circuit the batch.
        verify(regularizationService).approve(eq(okId), any(), eq("manager@test.com"));
        verify(regularizationService).approve(eq(failId), any(), eq("manager@test.com"));
    }

    @Test
    void bulkReject_allSucceed_returnsAllSucceededIds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(regularizationService.reject(any(), eq("Rejected in bulk"), eq("manager@test.com")))
                .thenReturn(RegularizationResponse.builder().status("REJECTED").build());

        BulkRegularizationResultResponse result = controller.bulkReject(
                BulkRejectRegularizationRequest.builder().ids(List.of(id1, id2)).comment("Rejected in bulk").build(),
                principal);

        assertEquals(List.of(id1, id2), result.getSucceededIds());
        assertTrue(result.getFailed().isEmpty());
    }
}

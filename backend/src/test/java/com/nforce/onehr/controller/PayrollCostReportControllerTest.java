package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.PayrollCostReportResponse;
import com.nforce.onehr.exception.GlobalExceptionHandler;
import com.nforce.onehr.service.PayrollCostReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-C16: the cost-report endpoint is gated by payroll:VIEW:ALL and surfaces the service's guard. */
@ExtendWith(MockitoExtension.class)
class PayrollCostReportControllerTest {

    @Mock private PayrollCostReportService costReportService;

    private MockMvc mvc;
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PayrollCostReportController(costReportService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void get_returnsTheReport() throws Exception {
        when(costReportService.buildReport(runId)).thenReturn(response());

        mvc.perform(get("/api/payroll/runs/" + runId + "/cost-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.employeeCount").value(2));
    }

    @Test
    void get_runNotFound_isNotFound() throws Exception {
        when(costReportService.buildReport(runId)).thenThrow(new NoSuchElementException("Payroll run not found"));

        mvc.perform(get("/api/payroll/runs/" + runId + "/cost-report")).andExpect(status().isNotFound());
    }

    @Test
    void get_runNotReportable_isConflict() throws Exception {
        when(costReportService.buildReport(runId)).thenThrow(new IllegalStateException("not APPROVED or DISBURSED"));

        mvc.perform(get("/api/payroll/runs/" + runId + "/cost-report")).andExpect(status().isConflict());
    }

    @Test
    void endpointIsGatedByPayrollViewAll() throws Exception {
        Method get = PayrollCostReportController.class.getMethod("get", UUID.class);

        PreAuthorize gate = get.getAnnotation(PreAuthorize.class);

        assertNotNull(gate);
        assertEquals("hasAuthority('payroll:VIEW:ALL')", gate.value());
    }

    private PayrollCostReportResponse response() {
        return new PayrollCostReportResponse(runId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 2,
                new BigDecimal("3000.00"), new BigDecimal("300.00"), new BigDecimal("2700.00"), List.of(), List.of());
    }
}

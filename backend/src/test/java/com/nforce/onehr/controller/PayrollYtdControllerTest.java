package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.YtdResponse;
import com.nforce.onehr.exception.GlobalExceptionHandler;
import com.nforce.onehr.service.PayrollYtdService;
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

/**
 * US-C17: the two YTD endpoints stay separate — the self-service one takes no id and has no
 * authority gate, the HR one is gated by payroll:VIEW:ALL.
 */
@ExtendWith(MockitoExtension.class)
class PayrollYtdControllerTest {

    @Mock private PayrollYtdService ytdService;

    private MockMvc mvc;
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PayrollYtdController(ytdService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void myYtd_usesThePrincipalAndAcceptsNoEmployeeId() throws Exception {
        when(ytdService.getMyYtd("emp@x.com")).thenReturn(response(employeeId));

        mvc.perform(get("/api/me/finances/ytd").principal(() -> "emp@x.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fiscalYear").value("FY 2026-27"))
                .andExpect(jsonPath("$.payslipCount").value(3));
    }

    @Test
    void ytdFor_returnsTheRequestedEmployee() throws Exception {
        when(ytdService.getYtdFor(employeeId)).thenReturn(response(employeeId));

        mvc.perform(get("/api/payroll/ytd/" + employeeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(employeeId.toString()));
    }

    @Test
    void ytdFor_unknownEmployee_isNotFound() throws Exception {
        when(ytdService.getYtdFor(employeeId)).thenThrow(new NoSuchElementException("Employee not found"));

        mvc.perform(get("/api/payroll/ytd/" + employeeId)).andExpect(status().isNotFound());
    }

    @Test
    void theHrEndpointIsGatedByPayrollViewAll_andTheSelfEndpointHasNoGate() throws Exception {
        Method hr = PayrollYtdController.class.getMethod("ytdFor", UUID.class);
        Method self = PayrollYtdController.class.getMethod("myYtd", java.security.Principal.class);

        PreAuthorize gate = hr.getAnnotation(PreAuthorize.class);
        assertNotNull(gate);
        assertEquals("hasAuthority('payroll:VIEW:ALL')", gate.value());
        assertNull(self.getAnnotation(PreAuthorize.class));
        // The self endpoint can only ever be "me": its parameter list has no employee id.
        assertEquals(1, self.getParameterCount());
    }

    private YtdResponse response(UUID id) {
        return new YtdResponse(id, "FY 2026-27", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), 3,
                new BigDecimal("300.00"), new BigDecimal("30.00"), new BigDecimal("270.00"), BigDecimal.ZERO,
                List.of(), List.of());
    }
}

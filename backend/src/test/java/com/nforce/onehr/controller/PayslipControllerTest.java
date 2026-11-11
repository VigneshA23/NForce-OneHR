package com.nforce.onehr.controller;

import com.nforce.onehr.exception.GlobalExceptionHandler;
import com.nforce.onehr.service.PayslipService;
import com.nforce.onehr.service.PayslipService.PayslipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-C12: the HTTP contract — PDF bytes with download/no-cache headers, and 403/404/400 mapping. */
@ExtendWith(MockitoExtension.class)
class PayslipControllerTest {

    @Mock private PayslipService payslipService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PayslipController(payslipService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void myPayslip_returnsPdfAsAnAttachmentThatIsNeverCached() throws Exception {
        byte[] pdf = "%PDF-1.4 test".getBytes();
        when(payslipService.getMyPayslipPdf("emp@x.com", "2026-11"))
                .thenReturn(new PayslipFile(pdf, "application/pdf", "payslip-EMP001-2026-11.pdf"));

        mvc.perform(get("/api/payslips/me/2026-11").principal(() -> "emp@x.com"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("payslip-EMP001-2026-11.pdf")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().bytes(pdf));
    }

    @Test
    void refusedCaller_getsForbidden() throws Exception {
        when(payslipService.getMyPayslipPdf("manager@x.com", "2026-11"))
                .thenThrow(new AccessDeniedException("You are not allowed to view this payslip"));

        mvc.perform(get("/api/payslips/me/2026-11").principal(() -> "manager@x.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownPeriod_isNotFound() throws Exception {
        when(payslipService.getMyPayslipPdf("emp@x.com", "2020-01"))
                .thenThrow(new NoSuchElementException("No payslip available for 2020-01"));

        mvc.perform(get("/api/payslips/me/2020-01").principal(() -> "emp@x.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedPeriod_isBadRequest() throws Exception {
        when(payslipService.getMyPayslipPdf("emp@x.com", "nope"))
                .thenThrow(new IllegalArgumentException("Period must be in yyyy-MM format, e.g. 2026-11"));

        mvc.perform(get("/api/payslips/me/nope").principal(() -> "emp@x.com"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void myPeriods_listsThePeriods() throws Exception {
        when(payslipService.listMyPeriods("emp@x.com")).thenReturn(List.of("2026-11", "2026-10"));

        mvc.perform(get("/api/payslips/me").principal(() -> "emp@x.com"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"2026-11\",\"2026-10\"]"));
    }
}

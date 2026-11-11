package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.EmployeeCompensationResponse;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.exception.GlobalExceptionHandler;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.EmployeeCompensationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-C02 self-service follow-up — the {@code /me/*} endpoints resolve "me" from the JWT
 * principal, same convention as PayslipControllerTest. */
@ExtendWith(MockitoExtension.class)
class EmployeeCompensationControllerTest {

    @Mock private EmployeeCompensationService employeeCompensationService;
    @Mock private UserRepository userRepository;

    private MockMvc mvc;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EmployeeCompensationController(employeeCompensationService, userRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void myCurrent_resolvesCallerAndReturnsTheirCompensation() throws Exception {
        when(userRepository.findByEmail("emp@x.com")).thenReturn(Optional.of(User.builder().id(userId).email("emp@x.com").build()));
        EmployeeCompensationResponse resp = EmployeeCompensationResponse.builder()
                .id(UUID.randomUUID()).employeeId(userId).ctc(new BigDecimal("1200000"))
                .structureName("Standard Structure").effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        when(employeeCompensationService.getCurrent(userId)).thenReturn(resp);

        mvc.perform(get("/api/payroll/employee-compensation/me/current").principal(() -> "emp@x.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Standard Structure")));
    }

    @Test
    void myCurrent_noCompensationAssignedYet_isNotFound() throws Exception {
        when(userRepository.findByEmail("emp@x.com")).thenReturn(Optional.of(User.builder().id(userId).email("emp@x.com").build()));
        when(employeeCompensationService.getCurrent(userId))
                .thenThrow(new NoSuchElementException("No current compensation for employee: " + userId));

        mvc.perform(get("/api/payroll/employee-compensation/me/current").principal(() -> "emp@x.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    void myHistory_resolvesCallerAndReturnsTheirHistory() throws Exception {
        when(userRepository.findByEmail("emp@x.com")).thenReturn(Optional.of(User.builder().id(userId).email("emp@x.com").build()));
        when(employeeCompensationService.getHistory(userId)).thenReturn(List.of(
                EmployeeCompensationResponse.builder().id(UUID.randomUUID()).employeeId(userId).ctc(new BigDecimal("1200000")).build()));

        mvc.perform(get("/api/payroll/employee-compensation/me/history").principal(() -> "emp@x.com"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownActor_isNotFound() throws Exception {
        when(userRepository.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

        mvc.perform(get("/api/payroll/employee-compensation/me/current").principal(() -> "ghost@x.com"))
                .andExpect(status().isNotFound());
    }
}

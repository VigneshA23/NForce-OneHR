package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.YtdResponse;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayslipLineItemRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * US-C17: YTD aggregation — the fiscal-year window handed to the queries (including a window that
 * straddles a year boundary), how the returned rows become the response, and the two access paths
 * (self resolves from identity; HR path takes an id).
 */
@ExtendWith(MockitoExtension.class)
class PayrollYtdServiceTest {

    @Mock private PayslipRepository payslipRepository;
    @Mock private PayslipLineItemRepository lineItemRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;

    private final UUID employeeId = UUID.randomUUID();

    private PayrollYtdService serviceOn(LocalDate today, int startMonth) {
        Clock clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new PayrollYtdService(payslipRepository, lineItemRepository, employeeRepository, userRepository,
                clock, startMonth);
    }

    // ── fiscal-year window handed to the queries ──

    @Test
    void queriesTheFiscalYearContainingToday_withOnlyApprovedAndDisbursedRuns() {
        stubRows(totals(0, null, null, null, null), List.of());

        serviceOn(LocalDate.of(2026, 11, 9), 4).ytd(employeeId, LocalDate.of(2026, 11, 9));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(payslipRepository).summarizeForEmployee(eq(employeeId), eq(LocalDate.of(2026, 4, 1)),
                eq(LocalDate.of(2027, 3, 31)), statuses.capture());
        assertEquals(Set.of(PayrollRunStatus.APPROVED, PayrollRunStatus.DISBURSED), Set.copyOf(statuses.getValue()));
        verify(lineItemRepository).sumByComponentForEmployee(eq(employeeId), eq(LocalDate.of(2026, 4, 1)),
                eq(LocalDate.of(2027, 3, 31)), anyCollection());
    }

    @Test
    void windowRollsOverAtTheFiscalYearBoundary() {
        stubRows(totals(0, null, null, null, null), List.of());
        PayrollYtdService service = serviceOn(LocalDate.of(2027, 3, 31), 4);

        YtdResponse lastDayOfYear = service.ytd(employeeId, LocalDate.of(2027, 3, 31));
        YtdResponse firstDayOfNext = service.ytd(employeeId, LocalDate.of(2027, 4, 1));

        assertEquals(LocalDate.of(2026, 4, 1), lastDayOfYear.fiscalYearStart());
        assertEquals(LocalDate.of(2027, 3, 31), lastDayOfYear.fiscalYearEnd());
        assertEquals(LocalDate.of(2027, 4, 1), firstDayOfNext.fiscalYearStart());
        assertEquals("FY 2026-27", lastDayOfYear.fiscalYear());
        assertEquals("FY 2027-28", firstDayOfNext.fiscalYear());
    }

    @Test
    void configuredStartMonthChangesTheWindow() {
        stubRows(totals(0, null, null, null, null), List.of());

        YtdResponse calendar = serviceOn(LocalDate.of(2026, 11, 9), 1).ytd(employeeId, LocalDate.of(2026, 11, 9));

        assertEquals(LocalDate.of(2026, 1, 1), calendar.fiscalYearStart());
        assertEquals(LocalDate.of(2026, 12, 31), calendar.fiscalYearEnd());
    }

    // ── aggregation → response ──

    @Test
    void buildsTotalsAndSplitsComponentsIntoEarningsAndDeductions() {
        stubRows(totals(8, "800000.00", "16000.00", "784000.00", "2.5"), Arrays.asList(
                row("HRA", SalaryComponentType.EARNING, "200000.00"),
                row("Basic", SalaryComponentType.EARNING, "400000.00"),
                row("Incentives", SalaryComponentType.AD_HOC_EARNING, "50000.00"),
                row("Professional Tax", SalaryComponentType.DEDUCTION, "1600.00"),
                row("Advance Recovery", SalaryComponentType.AD_HOC_DEDUCTION, "14400.00")));

        YtdResponse r = serviceOn(LocalDate.of(2026, 11, 9), 4).ytd(employeeId, LocalDate.of(2026, 11, 9));

        assertEquals(employeeId, r.employeeId());
        assertEquals(8, r.payslipCount());
        assertEquals(0, new BigDecimal("800000.00").compareTo(r.grossPay()));
        assertEquals(0, new BigDecimal("16000.00").compareTo(r.totalDeductions()));
        assertEquals(0, new BigDecimal("784000.00").compareTo(r.netPay()));
        assertEquals(0, new BigDecimal("2.5").compareTo(r.lopDays()));
        assertEquals(List.of("Basic", "HRA", "Incentives"), r.earnings().stream().map(c -> c.name()).toList());
        assertEquals(List.of("Advance Recovery", "Professional Tax"), r.deductions().stream().map(c -> c.name()).toList());
    }

    @Test
    void employerSideComponentTypes_areNeverListed() {
        stubRows(totals(1, "100.00", "0.00", "100.00", "0"), Arrays.asList(
                row("Basic", SalaryComponentType.EARNING, "100.00"),
                row("Employer PF", "CONTRIBUTION", "1800.00")));

        YtdResponse r = serviceOn(LocalDate.of(2026, 11, 9), 4).ytd(employeeId, LocalDate.of(2026, 11, 9));

        assertEquals(1, r.earnings().size());
        assertTrue(r.deductions().isEmpty());
    }

    @Test
    void employeeWithNoProcessedPayslips_hasZeroYtd_notAnError() {
        stubRows(totals(0, null, null, null, null), List.of());

        YtdResponse r = serviceOn(LocalDate.of(2026, 11, 9), 4).ytd(employeeId, LocalDate.of(2026, 11, 9));

        assertEquals(0, r.payslipCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.grossPay()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.netPay()));
        assertTrue(r.earnings().isEmpty() && r.deductions().isEmpty());
    }

    // ── access paths ──

    @Test
    void myYtd_isResolvedFromTheCallersIdentity() {
        User user = new User();
        user.setId(employeeId);
        when(userRepository.findByEmail("emp@x.com")).thenReturn(Optional.of(user));
        stubRows(totals(0, null, null, null, null), List.of());

        YtdResponse r = serviceOn(LocalDate.of(2026, 11, 9), 4).getMyYtd("emp@x.com");

        assertEquals(employeeId, r.employeeId());
        verifyNoInteractions(employeeRepository);
    }

    @Test
    void ytdFor_unknownEmployee_isNotFound() {
        when(employeeRepository.existsById(employeeId)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> serviceOn(LocalDate.of(2026, 11, 9), 4).getYtdFor(employeeId));

        verifyNoInteractions(payslipRepository, lineItemRepository);
    }

    @Test
    void ytdFor_knownEmployee_returnsTheirFigures() {
        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        stubRows(totals(2, "10.00", "1.00", "9.00", "0"), List.of());

        YtdResponse r = serviceOn(LocalDate.of(2026, 11, 9), 4).getYtdFor(employeeId);

        assertEquals(employeeId, r.employeeId());
        assertEquals(2, r.payslipCount());
    }

    @Test
    void startMonthOutsideOneToTwelve_failsAtConstruction() {
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        assertThrows(IllegalArgumentException.class, () -> new PayrollYtdService(payslipRepository,
                lineItemRepository, employeeRepository, userRepository, clock, 0));
        assertThrows(IllegalArgumentException.class, () -> new PayrollYtdService(payslipRepository,
                lineItemRepository, employeeRepository, userRepository, clock, 13));
    }

    // ── helpers ──

    private void stubRows(Object[] totalsRow, List<Object[]> componentRows) {
        lenient().when(payslipRepository.summarizeForEmployee(eq(employeeId), any(), any(), anyCollection()))
                .thenReturn(Collections.singletonList(totalsRow));
        lenient().when(lineItemRepository.sumByComponentForEmployee(eq(employeeId), any(), any(), anyCollection()))
                .thenReturn(componentRows);
    }

    private static Object[] totals(long count, String gross, String deductions, String net, String lop) {
        return new Object[] {count, dec(gross), dec(deductions), dec(net), dec(lop)};
    }

    private static Object[] row(String name, String type, String amount) {
        return new Object[] {name, type, new BigDecimal(amount)};
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }
}

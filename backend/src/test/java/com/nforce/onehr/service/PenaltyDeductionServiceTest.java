package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Section 7/48: Loss of Pay vs Paid Leave (with priority cascade + insufficient-balance fallback). */
@ExtendWith(MockitoExtension.class)
class PenaltyDeductionServiceTest {

    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private LeaveBalanceRepository leaveBalanceRepository;

    private PenaltyDeductionService service;
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PenaltyDeductionService(leaveTypeRepository, leaveBalanceRepository, new AuditSnapshotSerializer(new ObjectMapper()));
    }

    private AttendancePenalty penalty(BigDecimal deductionDays) {
        return AttendancePenalty.builder()
                .employeeUserId(employeeId).incidentDate(LocalDate.of(2026, 8, 15))
                .deductionDays(deductionDays).build();
    }

    private LeaveType leaveType(String code) {
        return LeaveType.builder().id(UUID.randomUUID()).code(code).name(code).build();
    }

    private LeaveBalance balance(BigDecimal total, BigDecimal used) {
        return LeaveBalance.builder().totalDays(total).usedDays(used).build();
    }

    @Test
    void lossOfPay_setsLopDaysOnly_noLeaveBalanceTouched() {
        AttendancePenalty p = penalty(new BigDecimal("2"));

        service.apply(p, "LOSS_OF_PAY", "SICK,CASUAL");

        assertEquals("LOSS_OF_PAY", p.getDeductionMethod());
        assertEquals(BigDecimal.ZERO, p.getLeaveDeductionDays());
        assertEquals(new BigDecimal("2"), p.getLopDays());
        org.mockito.Mockito.verifyNoInteractions(leaveTypeRepository, leaveBalanceRepository);
    }

    @Test
    void zeroDeductionDays_noOp() {
        AttendancePenalty p = penalty(BigDecimal.ZERO);

        service.apply(p, "PAID_LEAVE", "SICK");

        assertEquals(BigDecimal.ZERO, p.getLeaveDeductionDays());
        assertEquals(BigDecimal.ZERO, p.getLopDays());
        org.mockito.Mockito.verifyNoInteractions(leaveTypeRepository, leaveBalanceRepository);
    }

    @Test
    void paidLeave_sufficientBalanceInFirstType_deductsFromLeaveOnly() {
        AttendancePenalty p = penalty(new BigDecimal("1"));
        LeaveType sick = leaveType("SICK");
        when(leaveTypeRepository.findByCode("SICK")).thenReturn(Optional.of(sick));
        LeaveBalance sickBalance = balance(new BigDecimal("5"), new BigDecimal("1"));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeId, sick.getId(), 2026))
                .thenReturn(Optional.of(sickBalance));

        service.apply(p, "PAID_LEAVE", "SICK,CASUAL");

        assertEquals("PAID_LEAVE", p.getDeductionMethod());
        assertEquals(new BigDecimal("1"), p.getLeaveDeductionDays());
        assertEquals(BigDecimal.ZERO, p.getLopDays());
        assertEquals(new BigDecimal("2"), sickBalance.getUsedDays());
        assertNotNull(p.getLeaveBreakdown());
    }

    @Test
    void paidLeave_firstTypeExhausted_cascadesToNextType() {
        AttendancePenalty p = penalty(new BigDecimal("2"));
        LeaveType sick = leaveType("SICK");
        LeaveType casual = leaveType("CASUAL");
        when(leaveTypeRepository.findByCode("SICK")).thenReturn(Optional.of(sick));
        when(leaveTypeRepository.findByCode("CASUAL")).thenReturn(Optional.of(casual));
        // Sick balance fully exhausted already.
        LeaveBalance sickBalance = balance(new BigDecimal("3"), new BigDecimal("3"));
        LeaveBalance casualBalance = balance(new BigDecimal("5"), BigDecimal.ZERO);
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeId, sick.getId(), 2026))
                .thenReturn(Optional.of(sickBalance));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeId, casual.getId(), 2026))
                .thenReturn(Optional.of(casualBalance));

        service.apply(p, "PAID_LEAVE", "SICK,CASUAL");

        assertEquals(new BigDecimal("2"), p.getLeaveDeductionDays());
        assertEquals(BigDecimal.ZERO, p.getLopDays());
        assertEquals(new BigDecimal("3"), sickBalance.getUsedDays(), "sick balance untouched — already exhausted");
        assertEquals(new BigDecimal("2"), casualBalance.getUsedDays());
    }

    @Test
    void paidLeave_insufficientAcrossAllConfiguredTypes_remainderBecomesLossOfPay() {
        AttendancePenalty p = penalty(new BigDecimal("3"));
        LeaveType sick = leaveType("SICK");
        when(leaveTypeRepository.findByCode("SICK")).thenReturn(Optional.of(sick));
        LeaveBalance sickBalance = balance(new BigDecimal("5"), new BigDecimal("4")); // only 1 day available
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeId, sick.getId(), 2026))
                .thenReturn(Optional.of(sickBalance));

        service.apply(p, "PAID_LEAVE", "SICK");

        assertEquals(new BigDecimal("1"), p.getLeaveDeductionDays());
        assertEquals(new BigDecimal("2"), p.getLopDays());
        assertEquals(new BigDecimal("5"), sickBalance.getUsedDays(), "fully exhausted, capped at total");
    }

    @Test
    void paidLeave_noPriorityOrderConfigured_entirelyBecomesLossOfPay() {
        AttendancePenalty p = penalty(new BigDecimal("1"));

        service.apply(p, "PAID_LEAVE", null);

        assertEquals(BigDecimal.ZERO, p.getLeaveDeductionDays());
        assertEquals(new BigDecimal("1"), p.getLopDays());
        org.mockito.Mockito.verifyNoInteractions(leaveTypeRepository, leaveBalanceRepository);
    }

    @Test
    void paidLeave_unknownLeaveTypeCode_skippedGracefully() {
        AttendancePenalty p = penalty(new BigDecimal("1"));
        lenient().when(leaveTypeRepository.findByCode(any())).thenReturn(Optional.empty());

        service.apply(p, "PAID_LEAVE", "UNKNOWN");

        assertEquals(BigDecimal.ZERO, p.getLeaveDeductionDays());
        assertEquals(new BigDecimal("1"), p.getLopDays());
    }
}

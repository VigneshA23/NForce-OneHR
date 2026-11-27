package com.nforce.onehr.service;

import com.nforce.onehr.entity.AdHocPayment;
import com.nforce.onehr.entity.LeaveEncashment;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.repository.AdHocPaymentRepository;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.LeaveEncashmentRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.PayrollRunRepository;
import com.nforce.onehr.repository.SalaryComponentRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** LV-05: a confirmed year-end encashment reaches the next payroll draft as a "Leave Encashment" ad-hoc payment. */
@ExtendWith(MockitoExtension.class)
class LeaveEncashmentServiceTest {

    @Mock private EmployeeCompensationRepository compensationRepo;
    @Mock private SalaryStructureComponentRepository structureRepo;
    @Mock private SalaryComponentRepository componentRepo;
    @Mock private LeaveEncashmentRepository encashmentRepo;
    @Mock private AdHocPaymentRepository adHocRepo;
    @Mock private PayrollRunRepository payrollRunRepo;
    @Mock private LeaveTypeRepository typeRepo;

    private LeaveEncashmentService service;
    private final UUID emp = UUID.randomUUID(), hr = UUID.randomUUID();
    private final LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).name("Paid Leave").build();
    private final PayrollRun run = PayrollRun.builder().id(UUID.randomUUID()).status(PayrollRunStatus.DRAFT)
            .cycleStart(LocalDate.of(2026, 1, 1)).cycleEnd(LocalDate.of(2026, 1, 31)).build();

    @BeforeEach
    void setUp() {
        service = new LeaveEncashmentService(compensationRepo, structureRepo, new SalaryStructureResolutionService(), componentRepo,
                encashmentRepo, adHocRepo, payrollRunRepo, typeRepo);
    }

    private LeaveEncashment pending() {
        return LeaveEncashment.builder().id(UUID.randomUUID()).employeeUserId(emp).leaveTypeId(paid.getId()).fromYear(2025)
                .days(new BigDecimal("3")).amount(new BigDecimal("3000")).status(LeaveEncashment.PENDING).createdBy(hr).build();
    }

    @Test
    void aDraftPicksUpPendingEncashments_asLeaveEncashmentPayments() {
        LeaveEncashment e = pending();
        when(encashmentRepo.findByStatusAndEmployeeUserIdIn(LeaveEncashment.PENDING, List.of(emp))).thenReturn(List.of(e));
        when(componentRepo.findByNameIgnoreCase("Leave Encashment")).thenReturn(Optional.of(SalaryComponent.builder()
                .id(UUID.randomUUID()).name("Leave Encashment").type(SalaryComponentType.AD_HOC_EARNING).active(true).build()));
        when(typeRepo.findAllById(any())).thenReturn(List.of(paid));
        when(adHocRepo.save(any())).thenAnswer(inv -> {
            AdHocPayment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        assertEquals(1, service.attachPending(run, List.of(emp), UUID.randomUUID()));

        ArgumentCaptor<AdHocPayment> c = ArgumentCaptor.forClass(AdHocPayment.class);
        verify(adHocRepo).save(c.capture());
        assertEquals(0, new BigDecimal("3000").compareTo(c.getValue().getAmount()));
        assertEquals("Leave encashment: 3 day(s) of Paid Leave (2025)", c.getValue().getReason());
        assertEquals(hr, c.getValue().getAddedBy());
        assertEquals(LeaveEncashment.ADDED, e.getStatus());
        assertEquals(run.getId(), e.getPayrollRunId());
        assertEquals(c.getValue().getId(), e.getAdHocPaymentId());
    }

    @Test
    void withoutTheComponent_theEncashmentWaits() {
        LeaveEncashment e = pending();
        when(encashmentRepo.findByStatusAndEmployeeUserIdIn(any(), any())).thenReturn(List.of(e));
        when(componentRepo.findByNameIgnoreCase("Leave Encashment")).thenReturn(Optional.empty());
        assertEquals(0, service.attachPending(run, List.of(emp), hr));
        assertEquals(LeaveEncashment.PENDING, e.getStatus());
        verify(adHocRepo, never()).save(any());
    }

    @Test
    void rollback_removesThePaymentFromADraft_butIsBlockedOnceTheRunMovedOn() {
        LeaveEncashment e = pending();
        e.setStatus(LeaveEncashment.ADDED);
        e.setPayrollRunId(run.getId());
        e.setAdHocPaymentId(UUID.randomUUID());
        when(payrollRunRepo.findById(run.getId())).thenReturn(Optional.of(run));
        assertNull(service.blockingReason(e));
        AdHocPayment payment = AdHocPayment.builder().id(e.getAdHocPaymentId()).build();
        when(adHocRepo.findById(e.getAdHocPaymentId())).thenReturn(Optional.of(payment));
        service.cancel(e);
        verify(adHocRepo).delete(payment);
        assertEquals(LeaveEncashment.CANCELLED, e.getStatus());

        run.setStatus(PayrollRunStatus.SUBMITTED);
        e.setStatus(LeaveEncashment.ADDED);
        assertTrue(service.blockingReason(e).contains("submitted payroll run"));
    }
}

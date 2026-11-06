package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayrollProrationResponse;
import com.nforce.onehr.dto.payroll.RecomputeProrationRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayrollProrationRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as PayrollLopCalculationServiceTest. */
@ExtendWith(MockitoExtension.class)
class PayrollProrationServiceTest {

    @Mock private PayrollProrationRepository payrollProrationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PayGroupRepository payGroupRepository;
    @Mock private LeaveBalanceAvailabilityService leaveBalanceAvailabilityService;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private PayrollProrationService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID employeeId = UUID.randomUUID();
    private final UUID payGroupId = UUID.randomUUID();

    // Same 28-day same-month cycle used by PayrollLopCalculationServiceTest.
    private final LocalDate referenceDate = LocalDate.of(2026, 6, 15);
    private final LocalDate cycleStart = LocalDate.of(2026, 6, 1);
    private final LocalDate cycleEnd = LocalDate.of(2026, 6, 28);

    @BeforeEach
    void setUp() {
        service = new PayrollProrationService(payrollProrationRepository, employeeRepository, payGroupRepository,
                leaveBalanceAvailabilityService, userRepository, auditService, auditSnapshot);

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");

        PayGroup payGroup = PayGroup.builder().id(payGroupId).cycleStartDay(1).cycleEndDay(28).build();
        lenient().when(payGroupRepository.findById(payGroupId)).thenReturn(Optional.of(payGroup));

        lenient().when(payrollProrationRepository.save(any(PayrollProration.class))).thenAnswer(inv -> {
            PayrollProration p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
    }

    private Employee employee(LocalDate joiningDate, LocalDate lastWorkingDay) {
        Employee e = Employee.builder().userId(employeeId).joiningDate(joiningDate).lastWorkingDay(lastWorkingDay).build();
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(e));
        return e;
    }

    private RecomputeProrationRequest req() {
        RecomputeProrationRequest r = new RecomputeProrationRequest();
        r.setEmployeeId(employeeId);
        r.setPayGroupId(payGroupId);
        r.setReferenceDate(referenceDate);
        return r;
    }

    @Test
    void recompute_fullCycleEmployment_factorIsOne() {
        employee(LocalDate.of(2020, 1, 1), null);
        when(payrollProrationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());

        PayrollProrationResponse resp = service.recompute(req(), actorEmail);

        assertEquals(28, resp.getCycleDays());
        assertEquals(28, resp.getEmployedDays());
        assertEquals(0, new BigDecimal("1.0000").compareTo(resp.getProrationFactor()));
        assertEquals(ProrationType.FULL_CYCLE, resp.getProrationType());
        assertNull(resp.getLeaveEncashmentDays());
        verify(auditService).log(eq(actorId), eq("PAYROLL_PRORATION_COMPUTED"), any(), isNull(), any());
    }

    @Test
    void recompute_joinerMidCycle_prorates() {
        // Joins on the 15th of a 1st-28th cycle: 14 employed days (15th-28th inclusive) of 28.
        employee(LocalDate.of(2026, 6, 15), null);
        when(payrollProrationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());

        PayrollProrationResponse resp = service.recompute(req(), actorEmail);

        assertEquals(14, resp.getEmployedDays());
        assertEquals(0, new BigDecimal("0.5000").compareTo(resp.getProrationFactor()));
        assertEquals(ProrationType.JOINER, resp.getProrationType());
        assertNull(resp.getLeaveEncashmentDays());
    }

    @Test
    void recompute_exitMidCycle_pronatesAndEncashesLeave() {
        // Joined long ago, exits on the 10th of this cycle: days 1st-10th inclusive = 10 of 28.
        employee(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 6, 10));
        when(payrollProrationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        when(leaveBalanceAvailabilityService.availablePaidDays(employeeId, 2026)).thenReturn(new BigDecimal("3.5"));

        PayrollProrationResponse resp = service.recompute(req(), actorEmail);

        assertEquals(10, resp.getEmployedDays());
        assertEquals(ProrationType.EXIT, resp.getProrationType());
        assertEquals(0, new BigDecimal("3.5").compareTo(resp.getLeaveEncashmentDays()));
    }

    @Test
    void recompute_joinsAndExitsInSameCycle_bothFlagged() {
        // Joins the 5th, exits the 20th: 16 days of 28.
        employee(LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 20));
        when(payrollProrationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        when(leaveBalanceAvailabilityService.availablePaidDays(employeeId, 2026)).thenReturn(BigDecimal.ZERO);

        PayrollProrationResponse resp = service.recompute(req(), actorEmail);

        assertEquals(16, resp.getEmployedDays());
        assertEquals(ProrationType.JOINER_AND_EXIT, resp.getProrationType());
    }

    @Test
    void recompute_notYetEmployedDuringCycle_rejected() {
        employee(LocalDate.of(2026, 7, 1), null); // joins after this cycle ends

        assertThrows(IllegalArgumentException.class, () -> service.recompute(req(), actorEmail));
        verify(payrollProrationRepository, never()).save(any());
    }

    @Test
    void recompute_alreadyExitedBeforeCycle_rejected() {
        employee(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 5, 1)); // exited before this cycle started

        assertThrows(IllegalArgumentException.class, () -> service.recompute(req(), actorEmail));
        verify(payrollProrationRepository, never()).save(any());
    }

    @Test
    void recompute_exitOnLastDayOfCycle_stillFullyEmployedForIt() {
        employee(LocalDate.of(2020, 1, 1), cycleEnd);
        when(payrollProrationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        when(leaveBalanceAvailabilityService.availablePaidDays(employeeId, 2026)).thenReturn(BigDecimal.ZERO);

        PayrollProrationResponse resp = service.recompute(req(), actorEmail);

        assertEquals(28, resp.getEmployedDays());
        assertEquals(0, new BigDecimal("1.0000").compareTo(resp.getProrationFactor()));
        assertEquals(ProrationType.EXIT, resp.getProrationType());
    }

    @Test
    void get_unknownCombination_throwsNoSuchElement() {
        when(payrollProrationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.get(employeeId, payGroupId, referenceDate));
    }
}

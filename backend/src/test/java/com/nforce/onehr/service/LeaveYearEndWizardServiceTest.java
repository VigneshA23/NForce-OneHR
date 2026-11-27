package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.LeaveConfigDtos.AdjustYearEndLineRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunDetail;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveCarryForwardTransfer;
import com.nforce.onehr.entity.LeaveEncashment;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveYearEndRun;
import com.nforce.onehr.entity.LeaveYearEndRunLine;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveCarryForwardTransferRepository;
import com.nforce.onehr.repository.LeaveEncashmentRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.LeaveYearEndRunLineRepository;
import com.nforce.onehr.repository.LeaveYearEndRunRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** LV-05: the year-end wizard — split order and caps, preview, adjust, confirm, rollback, the employee notice. */
@ExtendWith(MockitoExtension.class)
class LeaveYearEndWizardServiceTest {

    private static final int FROM = 2025;

    @Mock private LeaveYearEndRunRepository runRepo;
    @Mock private LeaveYearEndRunLineRepository lineRepo;
    @Mock private LeaveCarryForwardTransferRepository transferRepo;
    @Mock private LeaveEncashmentRepository encashmentRepo;
    @Mock private LeaveBalanceRepository balanceRepo;
    @Mock private LeaveTypeRepository typeRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private UserRepository userRepo;
    @Mock private LeaveYearEndService yearEnd;
    @Mock private LeaveBalanceService balances;
    @Mock private LeaveEncashmentService encashment;
    @Mock private NotificationService notifications;
    @Mock private EmailService email;
    @Mock private AuditService audit;

    private LeaveYearEndWizardService service;
    private final User hr = User.builder().id(UUID.randomUUID()).email("hr@test.com").build();
    private final LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave")
            .classification("PAID").entitlementBasis(LeaveType.BASIS_ANNUAL).systemKey(LeaveType.SYSTEM_PAID).build();
    private final UUID asha = UUID.randomUUID(), ravi = UUID.randomUUID(), leaver = UUID.randomUUID();
    private final List<LeaveYearEndRunLine> lines = new ArrayList<>();
    private final Map<String, LeaveBalance> table = new HashMap<>();
    private final List<LeaveCarryForwardTransfer> transfers = new ArrayList<>();
    private final List<LeaveEncashment> encashments = new ArrayList<>();
    private LeaveYearEndRun run;

    private static LeavePlanCatalog.TypeRule rule(boolean cf, String cfMax, String pct, Integer expiryMonths, Integer expMonth,
                                                  Integer expDay, boolean encash, String encashMax) {
        return new LeavePlanCatalog.TypeRule(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), new BigDecimal("15"),
                "YEARLY", cf, cfMax == null ? null : new BigDecimal(cfMax), expiryMonths, LeaveType.CF_FIRST,
                LeaveEligibilityService.Rules.EVERYONE, LeavePlanCatalog.Limits.NONE, LeavePlanCatalog.Accrual.DEFAULT,
                new LeavePlanCatalog.YearEnd(pct == null ? null : new BigDecimal(pct), expMonth, expDay, encash,
                        encashMax == null ? null : new BigDecimal(encashMax), "BASIC", 30));
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @BeforeEach
    void setUp() {
        service = new LeaveYearEndWizardService(runRepo, lineRepo, transferRepo, encashmentRepo, balanceRepo, typeRepo, employeeRepo,
                userRepo, yearEnd, balances, encashment, notifications, email, audit, new AuditSnapshotSerializer(new ObjectMapper()), 7, true);
        lenient().when(userRepo.findByEmail("hr@test.com")).thenReturn(Optional.of(hr));
        lenient().when(balances.today()).thenReturn(LocalDate.of(FROM + 1, 1, 5));
        lenient().when(typeRepo.findAll()).thenReturn(List.of(paid));
        lenient().when(runRepo.save(any())).thenAnswer(inv -> {
            LeaveYearEndRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            if (r.getCreatedAt() == null) r.setCreatedAt(LocalDateTime.now());
            run = r;
            return r;
        });
        lenient().when(runRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(run));
        lenient().when(lineRepo.saveAll(any())).thenAnswer(inv -> {
            for (LeaveYearEndRunLine l : inv.<Iterable<LeaveYearEndRunLine>>getArgument(0)) {
                if (l.getId() == null) l.setId(UUID.randomUUID());
                lines.add(l);
            }
            return lines;
        });
        lenient().when(lineRepo.findByRunId(any())).thenAnswer(inv -> lines);
        lenient().when(lineRepo.findById(any())).thenAnswer(inv -> lines.stream().filter(l -> l.getId().equals(inv.getArgument(0))).findFirst());
        lenient().when(employeeRepo.findNamesByUserIds(any())).thenReturn(List.of());
        lenient().when(employeeRepo.findCodesByUserIds(any())).thenReturn(List.of());
        lenient().when(balances.ensureBalance(any(), any(), anyInt())).thenAnswer(inv -> table.computeIfAbsent(
                inv.getArgument(0) + ":" + inv.getArgument(2), k -> LeaveBalance.builder().id(UUID.randomUUID())
                        .employeeUserId(inv.getArgument(0)).leaveType(paid).year(inv.getArgument(2)).totalDays(BigDecimal.ZERO)
                        .usedDays(BigDecimal.ZERO).carriedForwardDays(BigDecimal.ZERO).carriedForwardUsedDays(BigDecimal.ZERO)
                        .carriedForwardExpiredDays(BigDecimal.ZERO).build()));
        lenient().when(balanceRepo.findByEmployeeUserIdAndLeaveTypeIdAndYear(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0) + ":" + inv.getArgument(2))));
        lenient().when(transferRepo.save(any())).thenAnswer(inv -> {
            LeaveCarryForwardTransfer t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            transfers.add(t);
            return t;
        });
        lenient().when(transferRepo.findByRunId(any())).thenAnswer(inv -> transfers);
        lenient().when(transferRepo.findByEmployeeUserIdAndLeaveTypeIdAndFromYear(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(encashmentRepo.saveAll(any())).thenAnswer(inv -> {
            inv.<Iterable<LeaveEncashment>>getArgument(0).forEach(encashments::add);
            return encashments;
        });
        lenient().when(encashmentRepo.findByRunId(any())).thenAnswer(inv -> encashments);
    }

    // ── the split ──

    @Test
    void split_carriesUpToTheLowerCap_thenEncashesUpToItsCap_theRestLapses() {
        var s = LeaveYearEndWizardService.split(new BigDecimal("12"), Optional.of(rule(true, "10", "50", null, null, null, true, "4")));
        assertDays("6", s.carry());   // 50 % of 12 is lower than 10 days
        assertDays("4", s.encash());  // capped at 4
        assertDays("2", s.lapse());
        s = LeaveYearEndWizardService.split(new BigDecimal("12"), Optional.of(rule(true, "10", null, null, null, null, true, null)));
        assertDays("10", s.carry());
        assertDays("2", s.encash());
        assertDays("0", s.lapse());
        s = LeaveYearEndWizardService.split(new BigDecimal("12"), Optional.of(rule(false, null, null, null, null, null, false, null)));
        assertDays("0", s.carry());
        assertDays("12", s.lapse());
        s = LeaveYearEndWizardService.split(new BigDecimal("7.33"), Optional.of(rule(true, null, "25", null, null, null, false, null)));
        assertDays("1.83", s.carry()); // 25 % rounded down
        assertDays("5.50", s.lapse());
        assertDays("3", LeaveYearEndWizardService.split(new BigDecimal("3"), Optional.empty()).lapse());
    }

    @Test
    void expiry_onAFixedDate_orAfterMonths_orNever() {
        assertEquals(LocalDate.of(FROM + 1, 3, 31), LeaveYearEndService.expiry(Optional.of(rule(true, null, null, null, 3, 31, false, null)), FROM));
        assertEquals(LocalDate.of(FROM + 1, 6, 30), LeaveYearEndService.expiry(Optional.of(rule(true, null, null, 6, null, null, false, null)), FROM));
        assertNull(LeaveYearEndService.expiry(Optional.of(rule(true, null, null, null, null, null, false, null)), FROM));
        assertNull(LeaveYearEndService.expiry(Optional.of(rule(false, null, null, 6, null, null, false, null)), FROM));
    }

    // ── preview → adjust → confirm → rollback ──

    private void population() {
        when(employeeRepo.findActiveEmployeeUserIds()).thenReturn(List.of(asha, ravi, leaver));
        when(employeeRepo.findLeaveProfiles()).thenReturn(List.of(
                new LeaveEmployeeProfile(asha, "Female", null, null, "FULL_TIME", LocalDate.of(2020, 1, 1), null, true, null),
                new LeaveEmployeeProfile(ravi, "Male", null, null, "FULL_TIME", LocalDate.of(2020, 1, 1), null, true, null),
                new LeaveEmployeeProfile(leaver, "Male", null, null, "FULL_TIME", LocalDate.of(2020, 1, 1),
                        LocalDate.of(FROM, 11, 30), true, null)));
        when(transferRepo.findByFromYear(FROM)).thenReturn(List.of());
        LeavePlanCatalog.TypeRule r = rule(true, "5", null, null, 3, 31, true, "3");
        when(yearEnd.closingRule(any(), eq(paid), eq(FROM))).thenReturn(Optional.of(r));
        when(yearEnd.unusedAtYearEnd(any())).thenAnswer(inv -> {
            LeaveBalance b = inv.getArgument(0);
            return b.getEmployeeUserId().equals(asha) ? new BigDecimal("10") : BigDecimal.ZERO;
        });
        when(encashment.monthlyBase(any(), eq(LocalDate.of(FROM, 12, 31)), eq("BASIC"))).thenReturn(new BigDecimal("30000"));
    }

    @Test
    void preview_splitsEveryActiveEmployee_skipsLeavers_andPricesTheEncashment() {
        population();
        YearEndRunDetail d = service.preview(FROM, "hr@test.com");

        assertEquals(LeaveYearEndRun.PREVIEW, d.run().status());
        assertEquals(2, d.lines().size(), "the leaver is settled by the exit, not the year-end");
        assertEquals(1, d.leaversSkipped());
        LeaveYearEndRunLine a = lines.stream().filter(l -> l.getEmployeeUserId().equals(asha)).findFirst().orElseThrow();
        assertDays("5", a.getCarryDays());
        assertDays("3", a.getEncashDays());
        assertDays("2", a.getLapseDays());
        assertEquals(LocalDate.of(FROM + 1, 3, 31), a.getExpiresOn());
        assertDays("3000", a.getEncashAmount()); // 3 × 30,000 ÷ 30
        assertDays("10", d.run().closingDays());
    }

    @Test
    void preview_isRefusedForAYearNotEnded_orAlreadyConfirmed() {
        assertThrows(IllegalArgumentException.class, () -> service.preview(FROM + 1, "hr@test.com"));
        when(runRepo.findByFromYearAndStatus(FROM, LeaveYearEndRun.CONFIRMED)).thenReturn(Optional.of(new LeaveYearEndRun()));
        assertThrows(IllegalStateException.class, () -> service.preview(FROM, "hr@test.com"));
    }

    @Test
    void adjust_keepsCarryPlusEncashWithinTheClosingDays_andRecomputesLapseAndAmount() {
        population();
        service.preview(FROM, "hr@test.com");
        LeaveYearEndRunLine a = lines.stream().filter(l -> l.getEmployeeUserId().equals(asha)).findFirst().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> service.adjust(run.getId(), a.getId(),
                new AdjustYearEndLineRequest(new BigDecimal("8"), new BigDecimal("3")), "hr@test.com"));
        var dto = service.adjust(run.getId(), a.getId(), new AdjustYearEndLineRequest(new BigDecimal("7"), new BigDecimal("1")), "hr@test.com");
        assertDays("2", dto.lapseDays());
        assertDays("1000", dto.encashAmount());
        assertTrue(dto.adjusted());
        verify(audit).log(eq(hr.getId()), eq("LEAVE_YEAR_END_LINE_ADJUSTED"), eq(a.getId()), any(), any());
    }

    @Test
    void confirm_writesTransfers_nextYearRowsForEveryone_queuesTheEncashment_andTellsEachEmployee() {
        population();
        service.preview(FROM, "hr@test.com");
        when(userRepo.findAllById(any())).thenReturn(List.of(User.builder().id(asha).email("asha@test.com").build()));

        YearEndRunDetail d = service.confirm(run.getId(), "hr@test.com");

        assertEquals(LeaveYearEndRun.CONFIRMED, d.run().status());
        assertNotNull(d.run().rollbackUntil());
        assertEquals(2, transfers.size());
        LeaveCarryForwardTransfer t = transfers.stream().filter(x -> x.getEmployeeUserId().equals(asha)).findFirst().orElseThrow();
        assertDays("5", t.getCarriedDays());
        assertDays("3", t.getEncashedDays());
        assertDays("2", t.getLapsedDays());
        assertEquals(run.getId(), t.getRunId());
        assertDays("5", table.get(asha + ":" + (FROM + 1)).getCarriedForwardDays());
        assertNotNull(table.get(ravi + ":" + (FROM + 1)), "a next-year row even for someone carrying nothing");
        assertEquals(1, encashments.size());
        assertEquals(LeaveEncashment.PENDING, encashments.get(0).getStatus());
        assertDays("3000", encashments.get(0).getAmount());
        verify(notifications).send(eq(asha), eq("LEAVE_YEAR_END_OUTCOME"), any(), eq(
                "Paid Leave 2025: 5 day(s) carried forward to 2026 (use by 31 Mar 2026); 3 day(s) encashed (₹3,000.00, paid with your next salary); 2 day(s) lapsed."),
                eq("/leave"));
        verify(email).sendLeaveNoticeEmail(eq("asha@test.com"), any(), any(), any(), any(), eq("/leave"));
        assertThrows(IllegalStateException.class, () -> service.confirm(run.getId(), "hr@test.com"));
    }

    @Test
    void rollback_reversesTheCarryAndCancelsTheEncashment_butNotOnceCarriedDaysWereUsed() {
        population();
        service.preview(FROM, "hr@test.com");
        service.confirm(run.getId(), "hr@test.com");
        LeaveBalance next = table.get(asha + ":" + (FROM + 1));

        next.setCarriedForwardUsedDays(BigDecimal.ONE);
        IllegalStateException blocked = assertThrows(IllegalStateException.class, () -> service.rollback(run.getId(), "hr@test.com"));
        assertTrue(blocked.getMessage().contains("already used or lost carried-forward Paid Leave"), blocked.getMessage());

        next.setCarriedForwardUsedDays(BigDecimal.ZERO);
        YearEndRunDetail d = service.rollback(run.getId(), "hr@test.com");
        assertEquals(LeaveYearEndRun.ROLLED_BACK, d.run().status());
        assertDays("0", next.getCarriedForwardDays());
        assertNull(next.getCarriedForwardExpiresOn());
        verify(transferRepo).deleteAll(transfers);
        verify(encashment).cancel(encashments.get(0));
    }

    @Test
    void rollback_isRefusedAfterTheWindow_orWhenTheEncashmentIsInASubmittedPayrollRun() {
        population();
        service.preview(FROM, "hr@test.com");
        service.confirm(run.getId(), "hr@test.com");
        when(encashment.blockingReason(any())).thenReturn("its encashment is already in a submitted payroll run");
        assertThrows(IllegalStateException.class, () -> service.rollback(run.getId(), "hr@test.com"));

        run.setConfirmedAt(LocalDateTime.now().minusDays(8));
        IllegalStateException late = assertThrows(IllegalStateException.class, () -> service.rollback(run.getId(), "hr@test.com"));
        assertTrue(late.getMessage().contains("window"), late.getMessage());
    }

    @Test
    void encashmentAmount_isDaysTimesTheMonthlyBasisOverTheDivisor() {
        assertDays("3461.54", LeaveEncashmentService.amount(new BigDecimal("3"), new BigDecimal("30000"), 26));
        assertDays("1500", LeaveEncashmentService.amount(new BigDecimal("1.5"), new BigDecimal("30000"), 30));
    }
}

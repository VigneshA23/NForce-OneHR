package com.nforce.onehr.service;

import com.nforce.onehr.dto.LeaveConfigDtos.BalanceAdjustmentRequest;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveTypeClassification;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.service.LeaveOpeningBalanceService.UploadRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** YE-02: bulk upload of leave opening balances — template, row-level validation, all-or-nothing apply. */
@ExtendWith(MockitoExtension.class)
class LeaveOpeningBalanceServiceTest {

    @Mock private EmployeeRepository employeeRepo;
    @Mock private LeaveTypeRepository typeRepo;
    @Mock private LeaveBalanceRepository balanceRepo;
    @Mock private LeaveBalanceService balances;
    @Mock private LeaveTypeService leaveTypeService;

    private LeaveOpeningBalanceService service;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave").active(true).build();
    private final LeaveType unpaid = LeaveType.builder().id(UUID.randomUUID()).code("UNPAID").name("Unpaid Leave").active(true)
            .classification(LeaveTypeClassification.UNPAID).build();
    private static final int YEAR = LocalDate.now().getYear();
    private LeaveBalance aliceRow;

    @BeforeEach
    void setUp() {
        service = new LeaveOpeningBalanceService(employeeRepo, typeRepo, balanceRepo, balances,
                TestLeaveBalances.everyoneEligible(), leaveTypeService);
        lenient().when(balances.today()).thenReturn(LocalDate.now());
        lenient().when(employeeRepo.findActiveCodeRows()).thenReturn(List.of(
                new Object[]{alice, "EMP001", "Alice"}, new Object[]{bob, "EMP002", "Bob"}));
        lenient().when(typeRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(paid, unpaid));
        aliceRow = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(alice).leaveType(paid).year(YEAR)
                .totalDays(new BigDecimal("15")).usedDays(new BigDecimal("4")).build();
        lenient().when(balanceRepo.findByEmployeeUserIdInAndYear(any(), eq(YEAR))).thenReturn(List.of(aliceRow));
    }

    private static UploadRow row(int n, String code, String type, String credited, String cf, String reason) {
        return new UploadRow(n, code, type, credited, cf, reason);
    }

    @Test
    void template_listsEveryActiveEmployee_forEveryPaidYearlyType_withTheirCurrentFigures() {
        List<LeaveOpeningBalanceService.TemplateRow> rows = service.template(YEAR);

        assertEquals(2, rows.size()); // unpaid leave has no balance, so it isn't in the template
        assertEquals("EMP001", rows.get(0).employeeCode());
        assertEquals(0, new BigDecimal("15").compareTo(rows.get(0).currentCredited()));
        assertEquals(0, new BigDecimal("4").compareTo(rows.get(0).usedDays()));
        assertEquals(0, BigDecimal.ZERO.compareTo(rows.get(1).currentCredited())); // Bob: no row yet
        verify(balances, never()).ensureBalance(any(), any(), anyInt()); // reading the template creates nothing
    }

    @Test
    void validate_reportsEveryProblemPerRow_andSavesNothing() {
        UUID carol = UUID.randomUUID(), dan = UUID.randomUUID();
        when(employeeRepo.findActiveCodeRows()).thenReturn(List.of(new Object[]{alice, "EMP001", "Alice"},
                new Object[]{bob, "EMP002", "Bob"}, new Object[]{carol, "EMP003", "Carol"}, new Object[]{dan, "EMP004", "Dan"}));
        LeaveOpeningBalanceService.Validation v = service.validate(YEAR, List.of(
                row(3, "EMP999", "Paid Leave", "10", null, "x"),          // unknown employee
                row(4, "EMP002", "Unpaid Leave", "10", null, "x"),        // not a paid yearly type
                row(5, "EMP003", "Paid Leave", "10.3", null, "x"),        // not a multiple of 0.5
                row(6, "EMP001", "Paid Leave", "3", null, "Correction"),  // below the 4 days used
                row(7, "EMP004", "PAID", "12", "-1", ""),                 // negative CF, no reason
                row(8, "EMP004", "paid leave", "12", null, "dup"),        // duplicate of row 7 (code/name both match)
                row(9, "EMP002", "Paid Leave", "ten", null, "x")));       // not a number

        assertEquals(7, v.errorRows());
        assertEquals(List.of("New Credited must be a number"), v.results().get(6).errors());
        assertEquals(List.of("No active employee with code EMP999"), v.results().get(0).errors());
        assertTrue(v.results().get(1).errors().get(0).contains("not an active paid leave type"));
        assertEquals(List.of("New Credited must be a multiple of 0.5"), v.results().get(2).errors());
        assertEquals(List.of("New Credited can't be less than the 4 day(s) already used in " + YEAR), v.results().get(3).errors());
        assertEquals(List.of("New Carried Forward can't be negative", "Reason is required for a change"), v.results().get(4).errors());
        assertEquals(List.of("Duplicate row for this employee and leave type"), v.results().get(5).errors());
        verifyNoInteractions(leaveTypeService);
    }

    @Test
    void apply_refusesAFileWithAnyError_andChangesNothing() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.apply(YEAR, List.of(
                row(3, "EMP001", "Paid Leave", "18", null, "Joiner proration"),
                row(4, "EMP999", "Paid Leave", "10", null, "x")), "hr@test.com"));
        assertTrue(ex.getMessage().startsWith("1 row(s) have errors. Nothing was saved"));
        verifyNoInteractions(leaveTypeService);
        verify(balances, never()).ensureBalance(any(), any(), anyInt());
    }

    @Test
    void apply_changesOnlyTheRowsThatDiffer_throughTheAuditedAdjustment_creatingAMissingRow() {
        LeaveBalance bobRow = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(bob).leaveType(paid).year(YEAR)
                .totalDays(BigDecimal.ZERO).build();
        when(balances.ensureBalance(bob, paid, YEAR)).thenReturn(bobRow);

        LeaveOpeningBalanceService.ApplyResult r = service.apply(YEAR, List.of(
                row(3, "EMP001", "Paid Leave", "15", "0", null),            // same as today: unchanged, no reason needed
                row(4, "emp002", "Paid Leave", "7.5", "3", "Joined mid-year")), "hr@test.com");

        assertEquals(1, r.changes());
        ArgumentCaptor<BalanceAdjustmentRequest> req = ArgumentCaptor.forClass(BalanceAdjustmentRequest.class);
        verify(leaveTypeService).adjustBalance(eq(bobRow.getId()), req.capture(), eq("hr@test.com"));
        assertEquals(0, new BigDecimal("7.5").compareTo(req.getValue().entitlementDays()));
        assertEquals(0, new BigDecimal("3").compareTo(req.getValue().carriedForwardDays()));
        assertEquals("Opening balance upload: Joined mid-year", req.getValue().reason());
        verify(leaveTypeService, times(1)).adjustBalance(any(), any(), any());
    }

    @Test
    void uploadingTheSameFileAgain_changesNothing() {
        aliceRow.setTotalDays(new BigDecimal("18"));
        LeaveOpeningBalanceService.Validation v = service.validate(YEAR, List.of(row(3, "EMP001", "Paid Leave", "18", null, "Correction")));
        assertEquals(0, v.changes());
        assertEquals(0, v.errorRows());
        assertFalse(v.results().get(0).changed());
    }

    @Test
    void onlyTheCurrentOrNextLeaveYear() {
        assertThrows(IllegalArgumentException.class, () -> service.template(YEAR + 2));
        assertThrows(IllegalArgumentException.class, () -> service.validate(YEAR - 1, List.of(row(3, "EMP001", "Paid Leave", "1", null, "x"))));
    }

    @Test
    void nextYearsCarriedForward_warnsWhenCarryForwardIsAutomatic() {
        paid.setCarryForwardEnabled(true);
        when(balanceRepo.findByEmployeeUserIdInAndYear(any(), eq(YEAR + 1))).thenReturn(List.of());

        LeaveOpeningBalanceService.Validation v = service.validate(YEAR + 1, List.of(row(3, "EMP001", "Paid Leave", null, "5", "From old system")));

        assertEquals(0, v.errorRows());
        assertEquals(1, v.results().get(0).warnings().size());
        assertTrue(v.results().get(0).warnings().get(0).startsWith("Carry-forward is automatic for Paid Leave"));
    }
}

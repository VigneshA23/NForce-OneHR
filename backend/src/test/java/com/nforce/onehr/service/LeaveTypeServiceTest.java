package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.CreateLeaveTypeRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.BalanceAdjustmentRequest;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.UpdateLeaveTypeRequest;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveTypeClassification;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveEntitlementPolicyRepository;
import com.nforce.onehr.repository.LeaveOccurrenceRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Leave Configuration: the type catalogue (rules live in plans since LV-01), deletion safeguards, audited adjustments. */
@ExtendWith(MockitoExtension.class)
class LeaveTypeServiceTest {

    @Mock private LeaveTypeRepository typeRepo;
    @Mock private LeaveEntitlementPolicyRepository policyRepo;
    @Mock private LeaveBalanceRepository balanceRepo;
    @Mock private LeaveRequestRepository requestRepo;
    @Mock private LeaveOccurrenceRepository occurrenceRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private AuditService auditService;
    @Mock private LeaveYearEndService yearEndService;
    @Mock private LeaveAccrualService accrual;
    @Mock private com.nforce.onehr.repository.LeavePlanTypeRuleRepository planRuleRepo;

    private LeaveTypeService service;
    private final User admin = User.builder().id(UUID.randomUUID()).email("hr@test.com").build();
    private LeaveType annual;

    @BeforeEach
    void setUp() {
        LeaveBalanceService balances = TestLeaveBalances.over(typeRepo, balanceRepo, requestRepo, policyRepo, occurrenceRepo,
                new AttendanceProperties());
        service = new LeaveTypeService(typeRepo, planRuleRepo, balanceRepo, balances, yearEndService, accrual, TestLeaveBalances.everyoneEligible(), userRepo, employeeRepo,
                auditService, new AuditSnapshotSerializer(new ObjectMapper()));
        annual = LeaveType.builder().id(UUID.randomUUID()).code("ANNUAL").name("Annual Leave").build();
        lenient().when(userRepo.findByEmail("hr@test.com")).thenReturn(Optional.of(admin));
        lenient().when(typeRepo.save(any(LeaveType.class))).thenAnswer(inv -> {
            LeaveType t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        lenient().when(typeRepo.findById(annual.getId())).thenReturn(Optional.of(annual));
    }

    private CreateLeaveTypeRequest create(String code, String classification, String basis) {
        CreateLeaveTypeRequest r = new CreateLeaveTypeRequest();
        r.setCode(code);
        r.setName(code + " Leave");
        r.setClassification(classification);
        r.setEntitlementBasis(basis);
        return r;
    }

    // ── create / edit ──

    @Test
    void createPaidType_isOnlyACatalogueEntry_inNoPlanYet_andAudited() {
        LeaveTypeResponse r = service.create(create("study", "PAID", null), "hr@test.com");

        assertEquals("STUDY", r.getCode());
        assertEquals(LeaveType.BASIS_ANNUAL, r.getEntitlementBasis());
        assertEquals(List.of(), r.getPlanNames()); // LV-01: nobody can use it until a plan includes it
        verify(auditService).log(eq(admin.getId()), eq("LEAVE_TYPE_CREATED"), any(), isNull(), any());
    }

    @Test
    void createUnpaidType_isUnlimited() {
        LeaveTypeResponse r = service.create(create("UNPAID2", "UNPAID", null), "hr@test.com");
        assertEquals("UNLIMITED", r.getEntitlementBasis());
    }

    @Test
    void duplicateCode_isRejected() {
        when(typeRepo.existsByCodeIgnoreCase("ANNUAL")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.create(create("annual", "PAID", null), "hr@test.com"));
        verify(typeRepo, never()).save(any());
    }

    @Test
    void perOccurrenceType_needsAWindow() {
        assertThrows(IllegalArgumentException.class, () -> service.create(create("MAT2", "PAID", "PER_OCCURRENCE"), "hr@test.com"));
    }

    @Test
    void changingEntitlementBasis_ofAReferencedType_isRefused() {
        when(typeRepo.countReferences(annual.getId(), "ANNUAL")).thenReturn(3L);
        UpdateLeaveTypeRequest req = new UpdateLeaveTypeRequest();
        req.setEntitlementBasis("PER_OCCURRENCE");
        req.setOccurrenceWindowMonths(12);
        assertThrows(IllegalStateException.class, () -> service.update(annual.getId(), req, "hr@test.com"));
    }

    @Test
    void makingATypeUnpaid_switchesOffItsCarryForward() {
        annual.setCarryForwardEnabled(true);
        UpdateLeaveTypeRequest req = new UpdateLeaveTypeRequest();
        req.setClassification(LeaveTypeClassification.UNPAID);
        service.update(annual.getId(), req, "hr@test.com");
        assertFalse(annual.isCarryForwardEnabled());
    }

    @Test
    void paidAndUnpaid_canOnlySwitchWhileNothingUsesTheType() {
        LeaveType lop = LeaveType.builder().id(UUID.randomUUID()).code("LOP").name("LOP").classification("UNPAID").build();
        when(typeRepo.findById(lop.getId())).thenReturn(Optional.of(lop));
        UpdateLeaveTypeRequest req = new UpdateLeaveTypeRequest();
        req.setClassification(LeaveTypeClassification.PAID);
        when(typeRepo.countReferences(lop.getId(), "LOP")).thenReturn(2L);
        assertThrows(IllegalStateException.class, () -> service.update(lop.getId(), req, "hr@test.com"));

        when(typeRepo.countReferences(lop.getId(), "LOP")).thenReturn(0L);
        service.update(lop.getId(), req, "hr@test.com");
        assertTrue(lop.isPaid());
    }

    @Test
    void update_withNullClassification_leavesItUnchanged_andUnknownIdIsNotFound() {
        UpdateLeaveTypeRequest req = new UpdateLeaveTypeRequest();
        req.setName("Annual Leave (renamed)");
        service.update(annual.getId(), req, "hr@test.com");
        assertEquals(LeaveTypeClassification.PAID, annual.getClassification());
        assertEquals("Annual Leave (renamed)", annual.getName());

        UUID unknown = UUID.randomUUID();
        when(typeRepo.findById(unknown)).thenReturn(Optional.empty());
        assertThrows(java.util.NoSuchElementException.class, () -> service.update(unknown, req, "hr@test.com"));
    }

    // ── delete / deactivate ──

    @Test
    void referencedType_cannotBeDeleted_onlyDeactivated() {
        when(typeRepo.countReferences(annual.getId(), "ANNUAL")).thenReturn(42L);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.delete(annual.getId(), "hr@test.com"));
        assertTrue(ex.getMessage().contains("42") && ex.getMessage().contains("Deactivate"), ex.getMessage());
        verify(typeRepo, never()).delete(any());
    }

    @Test
    void unreferencedType_isDeleted_andAudited() {
        when(typeRepo.countReferences(annual.getId(), "ANNUAL")).thenReturn(0L);
        service.delete(annual.getId(), "hr@test.com");
        verify(typeRepo).delete(annual);
        verify(auditService).log(eq(admin.getId()), eq("LEAVE_TYPE_DELETED"), eq(annual.getId()), any(), isNull());
    }

    @Test
    void deactivateAndReactivate_areAudited() {
        service.setActive(annual.getId(), false, "hr@test.com");
        assertFalse(annual.isActive());
        service.setActive(annual.getId(), true, "hr@test.com");
        assertTrue(annual.isActive());
        verify(auditService).log(eq(admin.getId()), eq("LEAVE_TYPE_DEACTIVATED"), eq(annual.getId()), any(), any());
        verify(auditService).log(eq(admin.getId()), eq("LEAVE_TYPE_REACTIVATED"), eq(annual.getId()), any(), any());
    }

    // ── built-in types ──

    @Test
    void builtInTypes_cantBeDeletedDeactivatedOrReclassified_butCanBeRenamed() {
        LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave").systemKey(LeaveType.SYSTEM_PAID).build();
        when(typeRepo.findById(paid.getId())).thenReturn(Optional.of(paid));
        assertThrows(IllegalArgumentException.class, () -> service.delete(paid.getId(), "hr@test.com"));
        assertThrows(IllegalArgumentException.class, () -> service.setActive(paid.getId(), false, "hr@test.com"));
        UpdateLeaveTypeRequest reclassify = new UpdateLeaveTypeRequest();
        reclassify.setClassification("UNPAID");
        assertThrows(IllegalArgumentException.class, () -> service.update(paid.getId(), reclassify, "hr@test.com"));
        verify(typeRepo, never()).delete(any());

        UpdateLeaveTypeRequest rename = new UpdateLeaveTypeRequest();
        rename.setName("Earned Leave");
        service.update(paid.getId(), rename, "hr@test.com");
        assertEquals("Earned Leave", paid.getName());
        assertTrue(paid.isPaid());
    }

    // ── manual adjustment ──

    @Test
    void balanceAdjustment_isAuditedWithPreviousAndNewValueReasonAndAdministrator() {
        LeaveBalance b = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID()).leaveType(annual)
                .year(LocalDate.now().getYear()).totalDays(new BigDecimal("15")).usedDays(new BigDecimal("4")).build();
        when(balanceRepo.findById(b.getId())).thenReturn(Optional.of(b));
        when(balanceRepo.save(any(LeaveBalance.class))).thenAnswer(inv -> inv.getArgument(0));

        service.adjustBalance(b.getId(), new BalanceAdjustmentRequest(new BigDecimal("17"), new BigDecimal("2"), "Joined mid-year correction"),
                "hr@test.com");

        assertEquals(0, new BigDecimal("17").compareTo(b.getTotalDays()));
        assertEquals(0, new BigDecimal("2").compareTo(b.getCarriedForwardDays()));
        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(admin.getId()), eq("LEAVE_BALANCE_ADJUSTED"), eq(b.getId()), before.capture(), after.capture());
        assertTrue(before.getValue().contains("15"), before.getValue());
        assertTrue(after.getValue().contains("17") && after.getValue().contains("Joined mid-year correction"), after.getValue());
    }

    @Test
    void balanceAdjustment_belowWhatIsAlreadyUsed_isAllowed_theEmployeeThenOwesDays() {
        // V314: leave can be taken in advance of monthly credits, so credited < used is a legitimate state.
        LeaveBalance b = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID()).leaveType(annual)
                .year(2026).totalDays(new BigDecimal("15")).usedDays(new BigDecimal("6")).build();
        when(balanceRepo.findById(b.getId())).thenReturn(Optional.of(b));
        when(balanceRepo.save(any(LeaveBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        service.adjustBalance(b.getId(), new BalanceAdjustmentRequest(new BigDecimal("5"), BigDecimal.ZERO, "correction"), "hr@test.com");
        assertEquals(0, new BigDecimal("5").compareTo(b.getTotalDays()));
    }

    @Test
    void balanceAdjustment_carriedForwardBelowWhatIsUsed_isStillRefused() {
        LeaveBalance b = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID()).leaveType(annual)
                .year(2026).totalDays(new BigDecimal("15")).usedDays(BigDecimal.ZERO)
                .carriedForwardDays(new BigDecimal("4")).carriedForwardUsedDays(new BigDecimal("3")).build();
        when(balanceRepo.findById(b.getId())).thenReturn(Optional.of(b));
        assertThrows(IllegalArgumentException.class, () -> service.adjustBalance(b.getId(),
                new BalanceAdjustmentRequest(new BigDecimal("15"), new BigDecimal("2"), "oops"), "hr@test.com"));
        verify(balanceRepo, never()).save(any());
    }
}

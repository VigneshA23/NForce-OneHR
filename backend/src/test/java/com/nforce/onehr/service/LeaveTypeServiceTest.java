package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.CreateLeaveTypeRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.BalanceAdjustmentRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.CarryForwardSettingsRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.EntitlementChangeRequest;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.UpdateLeaveTypeRequest;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V310 Leave Configuration: types, deletion safeguards, entitlements, carry-forward settings, audited adjustments. */
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

    private LeaveTypeService service;
    private final User admin = User.builder().id(UUID.randomUUID()).email("hr@test.com").build();
    private LeaveType annual;

    @BeforeEach
    void setUp() {
        LeaveBalanceService balances = new LeaveBalanceService(typeRepo, balanceRepo, requestRepo, policyRepo, occurrenceRepo,
                new AttendanceProperties());
        service = new LeaveTypeService(typeRepo, policyRepo, balanceRepo, balances, yearEndService, userRepo, employeeRepo,
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

    private CreateLeaveTypeRequest create(String code, String classification, String basis, String days) {
        CreateLeaveTypeRequest r = new CreateLeaveTypeRequest();
        r.setCode(code);
        r.setName(code + " Leave");
        r.setClassification(classification);
        r.setEntitlementBasis(basis);
        r.setEntitlementDays(days == null ? null : new BigDecimal(days));
        return r;
    }

    // ── create / edit ──

    @Test
    void createPaidType_storesItsEntitlementAsAPolicy_andAudits() {
        LeaveTypeResponse r = service.create(create("study", "PAID", null, "15"), "hr@test.com");

        assertEquals("STUDY", r.getCode());
        assertEquals(LeaveType.BASIS_ANNUAL, r.getEntitlementBasis());
        ArgumentCaptor<LeaveEntitlementPolicy> p = ArgumentCaptor.forClass(LeaveEntitlementPolicy.class);
        verify(policyRepo).save(p.capture());
        assertEquals(0, new BigDecimal("15").compareTo(p.getValue().getDays()));
        verify(auditService).log(eq(admin.getId()), eq("LEAVE_TYPE_CREATED"), any(), isNull(), any());
    }

    @Test
    void createUnpaidType_isUnlimited_noPolicy() {
        LeaveTypeResponse r = service.create(create("UNPAID2", "UNPAID", null, null), "hr@test.com");
        assertEquals("UNLIMITED", r.getEntitlementBasis());
        assertNull(r.getCurrentEntitlementDays());
        verify(policyRepo, never()).save(any());
    }

    @Test
    void duplicateCode_isRejected() {
        when(typeRepo.existsByCodeIgnoreCase("ANNUAL")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.create(create("annual", "PAID", null, "15"), "hr@test.com"));
        verify(typeRepo, never()).save(any());
    }

    @Test
    void paidTypeWithoutEntitlement_isRejected_andPerOccurrenceNeedsAWindow() {
        assertThrows(IllegalArgumentException.class, () -> service.create(create("X", "PAID", null, null), "hr@test.com"));
        assertThrows(IllegalArgumentException.class, () -> service.create(create("MAT2", "PAID", "PER_OCCURRENCE", "182"), "hr@test.com"));
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
    void unpaidToPaid_needsAnEntitlement_whichBecomesItsPolicy() {
        LeaveType lop = LeaveType.builder().id(UUID.randomUUID()).code("LOP").name("LOP").classification("UNPAID").build();
        when(typeRepo.findById(lop.getId())).thenReturn(Optional.of(lop));
        UpdateLeaveTypeRequest req = new UpdateLeaveTypeRequest();
        req.setClassification(LeaveTypeClassification.PAID);
        assertThrows(IllegalArgumentException.class, () -> service.update(lop.getId(), req, "hr@test.com"));

        lop.setClassification("UNPAID");
        req.setEntitlementDays(new BigDecimal("12"));
        service.update(lop.getId(), req, "hr@test.com");
        assertTrue(lop.isPaid());
        verify(policyRepo).save(argThat(p -> new BigDecimal("12").compareTo(p.getDays()) == 0));
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

    // ── carry-forward ──

    @Test
    void carryForward_isRefusedForUnpaidAndMaternity_butAllowedForRegularPaid() {
        LeaveType lop = LeaveType.builder().id(UUID.randomUUID()).code("LOP").name("LOP").classification("UNPAID").build();
        LeaveType maternity = LeaveType.builder().id(UUID.randomUUID()).code("MATERNITY").name("Maternity")
                .entitlementBasis(LeaveType.BASIS_PER_OCCURRENCE).occurrenceWindowMonths(12).build();
        when(typeRepo.findById(lop.getId())).thenReturn(Optional.of(lop));
        when(typeRepo.findById(maternity.getId())).thenReturn(Optional.of(maternity));
        CarryForwardSettingsRequest on = new CarryForwardSettingsRequest(true, new BigDecimal("5"), 3, "CARRY_FORWARD_FIRST");

        assertThrows(IllegalArgumentException.class, () -> service.updateCarryForward(lop.getId(), on, "hr@test.com"));
        assertThrows(IllegalArgumentException.class, () -> service.updateCarryForward(maternity.getId(), on, "hr@test.com"));

        service.updateCarryForward(annual.getId(), on, "hr@test.com");
        assertTrue(annual.isCarryForwardEnabled());
        assertEquals(0, new BigDecimal("5").compareTo(annual.getCarryForwardMaxDays()));
        assertEquals(3, annual.getCarryForwardExpiryMonths());
        verify(auditService).log(eq(admin.getId()), eq("LEAVE_CARRY_FORWARD_CONFIG_CHANGED"), eq(annual.getId()), any(), any());
    }

    @Test
    void disablingCarryForward_neverTouchesBalances() {
        annual.setCarryForwardEnabled(true);
        service.updateCarryForward(annual.getId(), new CarryForwardSettingsRequest(false, null, null, null), "hr@test.com");
        assertFalse(annual.isCarryForwardEnabled());
        verifyNoInteractions(balanceRepo);
    }

    // ── entitlements ──

    @Test
    void entitlementChange_leavesExistingBalancesAlone_unlessAskedTo_andThenOnlyTheYearsItGoverns() {
        when(policyRepo.findByLeaveTypeIdAndEffectiveYear(annual.getId(), 2027)).thenReturn(Optional.empty());
        service.changeEntitlement(annual.getId(), new EntitlementChangeRequest(2027, new BigDecimal("18"), false), "hr@test.com");
        verify(balanceRepo, never()).findByLeaveTypeIdAndYearGreaterThanEqual(any(), any());

        LeaveBalance y2027 = LeaveBalance.builder().leaveType(annual).year(2027).totalDays(new BigDecimal("15")).usedDays(BigDecimal.ONE).build();
        LeaveBalance y2028 = LeaveBalance.builder().leaveType(annual).year(2028).totalDays(new BigDecimal("20")).usedDays(BigDecimal.ZERO).build();
        when(policyRepo.findByLeaveTypeIdOrderByEffectiveYearDesc(annual.getId())).thenReturn(List.of(
                LeaveEntitlementPolicy.builder().effectiveYear(2028).days(new BigDecimal("20")).build(),
                LeaveEntitlementPolicy.builder().effectiveYear(2027).days(new BigDecimal("18")).build()));
        when(balanceRepo.findByLeaveTypeIdAndYearGreaterThanEqual(annual.getId(), 2027)).thenReturn(List.of(y2027, y2028));

        service.changeEntitlement(annual.getId(), new EntitlementChangeRequest(2027, new BigDecimal("18"), true), "hr@test.com");
        assertEquals(0, new BigDecimal("18").compareTo(y2027.getTotalDays()));
        assertEquals(0, BigDecimal.ONE.compareTo(y2027.getUsedDays()));
        assertEquals(0, new BigDecimal("20").compareTo(y2028.getTotalDays())); // governed by the later 2028 policy
        verify(auditService, times(2)).log(eq(admin.getId()), eq("LEAVE_ENTITLEMENT_CHANGED"), eq(annual.getId()), any(), any());
    }

    @Test
    void unpaidLeave_hasNoEntitlementToConfigure() {
        LeaveType lop = LeaveType.builder().id(UUID.randomUUID()).code("LOP").name("LOP").classification("UNPAID").build();
        when(typeRepo.findById(lop.getId())).thenReturn(Optional.of(lop));
        assertThrows(IllegalArgumentException.class,
                () -> service.changeEntitlement(lop.getId(), new EntitlementChangeRequest(2027, BigDecimal.TEN, false), "hr@test.com"));
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
    void balanceAdjustment_belowWhatIsAlreadyUsed_isRefused() {
        LeaveBalance b = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID()).leaveType(annual)
                .year(2026).totalDays(new BigDecimal("15")).usedDays(new BigDecimal("6")).build();
        when(balanceRepo.findById(b.getId())).thenReturn(Optional.of(b));
        assertThrows(IllegalArgumentException.class, () -> service.adjustBalance(b.getId(),
                new BalanceAdjustmentRequest(new BigDecimal("5"), BigDecimal.ZERO, "oops"), "hr@test.com"));
        verify(balanceRepo, never()).save(any());
    }
}

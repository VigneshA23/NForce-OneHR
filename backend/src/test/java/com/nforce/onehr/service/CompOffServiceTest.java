package com.nforce.onehr.service;

import com.nforce.onehr.dto.compoff.CompOffDtos.AdjustRequest;
import com.nforce.onehr.entity.CompOffClaim;
import com.nforce.onehr.entity.CompOffCredit;
import com.nforce.onehr.entity.CompOffSettings;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.OtCalculation;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.CompOffClaimRepository;
import com.nforce.onehr.repository.CompOffCreditRepository;
import com.nforce.onehr.repository.CompOffEventRepository;
import com.nforce.onehr.repository.CompOffUtilizationRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.OtCalculationRepository;
import com.nforce.onehr.repository.OvertimeRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompOffServiceTest {

    @Mock private CompOffPolicyService policyService;
    @Mock private CompOffClaimRepository claimRepository;
    @Mock private CompOffCreditRepository creditRepository;
    @Mock private CompOffUtilizationRepository utilizationRepository;
    @Mock private CompOffEventRepository eventRepository;
    @Mock private OtCalculationRepository otCalculationRepository;
    @Mock private OvertimeRequestRepository overtimeRequestRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @InjectMocks private CompOffService service;

    private final UUID employeeId = UUID.randomUUID();
    private final LocalDate today = LocalDate.now();
    private CompOffSettings settings;

    @BeforeEach
    void setUp() {
        settings = CompOffSettings.defaults();
        settings.setCompOffEnabled(true);
        lenient().when(policyService.settings()).thenReturn(settings);
        lenient().when(policyService.compOffEnabled()).thenReturn(true);
        lenient().when(leaveTypeRepository.findByCode("COMP_OFF")).thenReturn(Optional.empty());
        lenient().when(creditRepository.save(any(CompOffCredit.class))).thenAnswer(i -> i.getArgument(0));
    }

    private CompOffCredit credit(String days, String used, int expiresInDays) {
        return CompOffCredit.builder().id(UUID.randomUUID()).employeeUserId(employeeId).creditDays(new BigDecimal(days))
                .usedDays(new BigDecimal(used)).status(CompOffCredit.AVAILABLE).earnedOn(today.minusDays(10))
                .expiresOn(today.plusDays(expiresInDays)).build();
    }

    private void available(CompOffCredit... credits) {
        when(creditRepository.findByEmployeeUserIdAndStatusOrderByExpiresOnAscCreatedAtAsc(employeeId, CompOffCredit.AVAILABLE))
                .thenReturn(List.of(credits));
    }

    @Test
    void creditFor_appliesHalfAndFullDayThresholds() {
        assertEquals(0, CompOffService.creditFor(settings, 239).signum());
        assertEquals(new BigDecimal("0.5"), CompOffService.creditFor(settings, 240));
        assertEquals(new BigDecimal("0.5"), CompOffService.creditFor(settings, 479));
        assertEquals(BigDecimal.ONE, CompOffService.creditFor(settings, 480));
    }

    @Test
    void validateLeaveRequest_rejectsMoreThanAvailable_ignoringExpiredCredits() {
        available(credit("1", "0", 5), credit("2", "0", -1)); // second one already lapsed
        assertThrows(IllegalArgumentException.class,
                () -> service.validateLeaveRequest(employeeId, today, new BigDecimal("1.5"), false, null));
        assertDoesNotThrow(() -> service.validateLeaveRequest(employeeId, today, BigDecimal.ONE, false, null));
    }

    @Test
    void validateLeaveRequest_blocksHalfDayWhenPolicyDisallowsIt_andWhenCompOffDisabled() {
        lenient().when(creditRepository.findByEmployeeUserIdAndStatusOrderByExpiresOnAscCreatedAtAsc(employeeId, CompOffCredit.AVAILABLE))
                .thenReturn(List.of(credit("2", "0", 5)));
        settings.setAllowPartialDay(false);
        assertThrows(IllegalArgumentException.class,
                () -> service.validateLeaveRequest(employeeId, today, new BigDecimal("0.5"), true, null));
        settings.setAllowPartialDay(true);
        settings.setCompOffEnabled(false);
        assertThrows(IllegalArgumentException.class,
                () -> service.validateLeaveRequest(employeeId, today, BigDecimal.ONE, false, null));
    }

    @Test
    void consumeForApprovedLeave_spendsSoonestExpiringCreditFirst() {
        CompOffCredit later = credit("1", "0", 30);
        CompOffCredit sooner = credit("1", "0", 3);
        available(sooner, later); // repository orders by expiry
        LeaveRequest request = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .startDate(today).endDate(today).totalDays(new BigDecimal("1.5")).build();

        service.consumeForApprovedLeave(request, UUID.randomUUID());

        assertEquals(0, new BigDecimal("1").compareTo(sooner.getUsedDays()));
        assertEquals(0, new BigDecimal("0.5").compareTo(later.getUsedDays()));
    }

    @Test
    void consumeForApprovedLeave_failsWithoutEnoughBalance_andSpendsNothing() {
        CompOffCredit only = credit("1", "0", 5);
        available(only);
        LeaveRequest request = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .startDate(today).endDate(today).totalDays(new BigDecimal("2")).build();
        assertThrows(IllegalStateException.class, () -> service.consumeForApprovedLeave(request, null));
        assertEquals(0, only.getUsedDays().signum());
        verify(utilizationRepository, never()).save(any());
    }

    @Test
    void expireAndNotify_expiresOnlyTheUnusedRemainder() {
        CompOffCredit partlyUsed = credit("1", "0.5", -1);
        CompOffCredit spent = credit("1", "1", -1);
        when(creditRepository.findByStatusAndExpiresOnBefore(CompOffCredit.AVAILABLE, today)).thenReturn(List.of(partlyUsed, spent));

        assertEquals(1, service.expireAndNotify(today));
        assertEquals(CompOffCredit.EXPIRED, partlyUsed.getStatus());
        assertEquals(0, new BigDecimal("0.5").compareTo(partlyUsed.getExpiredDays()));
        assertEquals(CompOffCredit.AVAILABLE, spent.getStatus());
    }

    @Test
    void choose_compOff_isBlockedWhenOvertimeAlreadyCalculatedForTheSameDay() {
        User employee = User.builder().id(employeeId).email("e@test.com").build();
        when(userRepository.findByEmail("e@test.com")).thenReturn(Optional.of(employee));
        CompOffClaim claim = CompOffClaim.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .workDate(today.minusDays(1)).creditDays(BigDecimal.ONE).dayType("WEEKLY_OFF").build();
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(otCalculationRepository.findByEmployeeUserIdAndWorkDate(employeeId, claim.getWorkDate()))
                .thenReturn(Optional.of(OtCalculation.builder().status(OtCalculation.CALCULATED).build()));

        assertThrows(IllegalStateException.class, () -> service.choose(claim.getId(), "COMP_OFF", "e@test.com"));
        assertEquals(CompOffClaim.PENDING, claim.getChoice());
        verify(creditRepository, never()).save(any(CompOffCredit.class));
    }

    @Test
    void choose_isFinal_aSettledDayCannotBeSwitched() {
        User employee = User.builder().id(employeeId).email("e@test.com").build();
        when(userRepository.findByEmail("e@test.com")).thenReturn(Optional.of(employee));
        CompOffClaim claim = CompOffClaim.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .workDate(today.minusDays(1)).creditDays(BigDecimal.ONE).dayType("HOLIDAY").choice(CompOffClaim.OT).build();
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        assertThrows(IllegalStateException.class, () -> service.choose(claim.getId(), "COMP_OFF", "e@test.com"));
    }

    @Test
    void adjust_deductBeyondBalance_isRejected() {
        User hr = User.builder().id(UUID.randomUUID()).email("hr@test.com").build();
        when(userRepository.findByEmail("hr@test.com")).thenReturn(Optional.of(hr));
        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        available(credit("1", "0", 5));
        assertThrows(IllegalStateException.class,
                () -> service.adjust(new AdjustRequest(employeeId, new BigDecimal("-2"), "correction"), "hr@test.com"));
    }
}

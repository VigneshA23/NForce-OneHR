package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveEntitlementPolicyRepository;
import com.nforce.onehr.repository.LeaveOccurrenceRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;

import static org.mockito.Mockito.mock;

/** A real {@link LeaveBalanceService} over the caller's mocked type/balance repositories (no pending requests, no policies). */
final class TestLeaveBalances {

    private TestLeaveBalances() {}

    static LeaveBalanceService over(LeaveTypeRepository types, LeaveBalanceRepository balances) {
        return new LeaveBalanceService(types, balances, mock(LeaveRequestRepository.class),
                mock(LeaveEntitlementPolicyRepository.class), mock(LeaveOccurrenceRepository.class), new AttendanceProperties());
    }
}

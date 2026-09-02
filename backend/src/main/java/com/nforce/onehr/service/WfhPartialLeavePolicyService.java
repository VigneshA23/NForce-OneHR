package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.UpdateWfhPartialLeavePolicyRequest;
import com.nforce.onehr.dto.attendance.WfhPartialLeavePolicyResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.entity.WfhPartialLeavePolicy;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.repository.WfhPartialLeavePolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The single source of truth for the WFH monthly-days limit and Partial Day monthly-minutes
 * limit — Super Admin configurable from the UI (see WfhPartialLeavePolicyController), replacing
 * what used to be hardcoded constants in AttendanceRequestService. Read fresh from the DB on
 * every call, deliberately not cached, so a saved change is reflected immediately for the very
 * next request/balance check — no redeploy, matching the story's explicit requirement.
 */
@Service
@RequiredArgsConstructor
public class WfhPartialLeavePolicyService {

    private static final short SINGLETON_ID = 1;

    private final WfhPartialLeavePolicyRepository policyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public WfhPartialLeavePolicyResponse getPolicy() {
        return toResponse(current());
    }

    /** Just the two numeric limits, no updatedByName resolution — for AttendanceRequestService's
     * per-submit/per-balance-check reads, avoiding the extra Employee lookup getPolicy() only
     * does for the settings screen's display. */
    @Transactional(readOnly = true)
    public WfhPartialLeaveLimits getLimits() {
        WfhPartialLeavePolicy policy = current();
        return new WfhPartialLeaveLimits(policy.getWfhMonthlyLimitDays(), policy.getPartialLeaveMonthlyLimitMinutes());
    }

    public record WfhPartialLeaveLimits(int wfhMonthlyLimitDays, int partialLeaveMonthlyLimitMinutes) {}

    @Transactional
    public WfhPartialLeavePolicyResponse updatePolicy(UpdateWfhPartialLeavePolicyRequest req, String actorEmail) {
        WfhPartialLeavePolicy policy = current();
        policy.setWfhMonthlyLimitDays(req.getWfhMonthlyLimitDays());
        policy.setPartialLeaveMonthlyLimitMinutes(req.getPartialLeaveMonthlyLimitMinutes());
        User actor = userRepository.findByEmail(actorEmail).orElse(null);
        policy.setUpdatedBy(actor != null ? actor.getId() : null);
        policy.setUpdatedAt(LocalDateTime.now());
        return toResponse(policyRepository.save(policy));
    }

    /** The row is seeded by V143, so this should always find it — falls back to building an
     * in-memory default (the same values V143 seeds) only for the pathological case of the row
     * having been manually deleted, rather than 500ing every WFH/Partial Day request. */
    private WfhPartialLeavePolicy current() {
        return policyRepository.findById(SINGLETON_ID).orElseGet(() -> WfhPartialLeavePolicy.builder()
                .id(SINGLETON_ID).wfhMonthlyLimitDays(2).partialLeaveMonthlyLimitMinutes(120)
                .updatedAt(LocalDateTime.now()).build());
    }

    private WfhPartialLeavePolicyResponse toResponse(WfhPartialLeavePolicy policy) {
        String updatedByName = null;
        if (policy.getUpdatedBy() != null) {
            updatedByName = employeeRepository.findById(policy.getUpdatedBy())
                    .map(Employee::getFullName).orElse(null);
        }
        return WfhPartialLeavePolicyResponse.builder()
                .wfhMonthlyLimitDays(policy.getWfhMonthlyLimitDays())
                .partialLeaveMonthlyLimitMinutes(policy.getPartialLeaveMonthlyLimitMinutes())
                .updatedByName(updatedByName)
                .updatedAt(policy.getUpdatedAt() != null ? policy.getUpdatedAt().toString() : null)
                .build();
    }
}

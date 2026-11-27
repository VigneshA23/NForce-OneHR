package com.nforce.onehr.service.assignment;

import com.nforce.onehr.entity.LeavePlan;
import com.nforce.onehr.repository.LeavePlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** LV-01: Leave Plans as a target domain of the shared rule engine ("assign by rule"). */
@Component
@RequiredArgsConstructor
public class LeavePlanAssignmentTarget implements AssignmentTargetResolver {

    private final LeavePlanRepository planRepository;

    @Override
    public String domain() {
        return AssignmentDomain.LEAVE_PLAN;
    }

    @Override
    public String permissionModule() {
        return "leave_configuration";
    }

    /**
     * leave_configuration has VIEW / CONFIGURE / EDIT, where EDIT is "adjust an employee's balance".
     * Creating, changing or deleting who gets which plan is CONFIGURE.
     */
    @Override
    public String permissionAction(String action) {
        return "VIEW".equals(action) ? "VIEW" : "CONFIGURE";
    }

    @Override
    public boolean isUsable(UUID targetId) {
        return planRepository.findById(targetId).map(LeavePlan::isActive).orElse(false);
    }

    @Override
    public Optional<String> targetName(UUID targetId) {
        return planRepository.findById(targetId).map(LeavePlan::getName);
    }
}

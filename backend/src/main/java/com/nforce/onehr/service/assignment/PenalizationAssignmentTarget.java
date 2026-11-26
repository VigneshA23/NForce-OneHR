package com.nforce.onehr.service.assignment;

import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** PN-04: Penalization Policies as the first target domain of the shared rule engine. */
@Component
@RequiredArgsConstructor
public class PenalizationAssignmentTarget implements AssignmentTargetResolver {

    private final PenalisationPolicyRepository policyRepository;

    @Override
    public String domain() {
        return AssignmentDomain.PENALIZATION;
    }

    @Override
    public String permissionModule() {
        return "penalization";
    }

    @Override
    public boolean isUsable(UUID targetId) {
        return policyRepository.findById(targetId).map(p -> "ACTIVE".equals(p.getStatus())).orElse(false);
    }

    @Override
    public Optional<String> targetName(UUID targetId) {
        return policyRepository.findById(targetId).map(PenalisationPolicy::getName);
    }
}

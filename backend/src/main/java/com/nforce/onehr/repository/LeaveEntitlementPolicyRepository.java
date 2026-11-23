package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveEntitlementPolicyRepository extends JpaRepository<LeaveEntitlementPolicy, UUID> {

    /** The policy in force for {@code year}: greatest effectiveYear <= year. */
    Optional<LeaveEntitlementPolicy> findFirstByLeaveTypeIdAndEffectiveYearLessThanEqualOrderByEffectiveYearDesc(
            UUID leaveTypeId, Integer year);

    Optional<LeaveEntitlementPolicy> findByLeaveTypeIdAndEffectiveYear(UUID leaveTypeId, Integer effectiveYear);

    List<LeaveEntitlementPolicy> findByLeaveTypeIdOrderByEffectiveYearDesc(UUID leaveTypeId);
}

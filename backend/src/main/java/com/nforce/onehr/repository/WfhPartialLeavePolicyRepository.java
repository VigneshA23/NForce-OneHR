package com.nforce.onehr.repository;

import com.nforce.onehr.entity.WfhPartialLeavePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WfhPartialLeavePolicyRepository extends JpaRepository<WfhPartialLeavePolicy, Short> {
}

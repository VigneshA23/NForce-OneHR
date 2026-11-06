package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PayGroupRepository extends JpaRepository<PayGroup, UUID> {

    List<PayGroup> findAllByOrderByNameAsc();
}

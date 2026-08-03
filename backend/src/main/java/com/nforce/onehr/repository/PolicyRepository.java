package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Policy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, Long> {

    List<Policy> findByActiveTrueOrderByPublishedAtDesc();

    List<Policy> findAllByOrderByPublishedAtDesc();

    List<Policy> findByTitleOrderByPublishedAtDesc(String title);
}

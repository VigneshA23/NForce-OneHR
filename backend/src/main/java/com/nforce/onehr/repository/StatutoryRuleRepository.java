package com.nforce.onehr.repository;

import com.nforce.onehr.entity.StatutoryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatutoryRuleRepository extends JpaRepository<StatutoryRule, UUID> {

    Optional<StatutoryRule> findByCountryAndRuleTypeAndEffectiveToIsNull(String country, String ruleType);

    List<StatutoryRule> findByCountryAndRuleTypeOrderByEffectiveFromDesc(String country, String ruleType);

    // Every currently-effective rule across every (country, ruleType) — backs a plain admin list.
    List<StatutoryRule> findByEffectiveToIsNullOrderByCountryAscRuleTypeAsc();

    @Modifying
    @Query("UPDATE StatutoryRule r SET r.effectiveTo = :effectiveTo "
         + "WHERE r.country = :country AND r.ruleType = :ruleType AND r.effectiveTo IS NULL")
    void closeCurrentEntry(@Param("country") String country, @Param("ruleType") String ruleType,
                            @Param("effectiveTo") LocalDate effectiveTo);
}

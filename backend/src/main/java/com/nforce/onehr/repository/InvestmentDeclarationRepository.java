package com.nforce.onehr.repository;

import com.nforce.onehr.entity.InvestmentDeclaration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestmentDeclarationRepository extends JpaRepository<InvestmentDeclaration, UUID> {

    Optional<InvestmentDeclaration> findByEmployeeIdAndFinancialYearAndSection(UUID employeeId, String financialYear, String section);

    List<InvestmentDeclaration> findByEmployeeIdOrderByFinancialYearDescSectionAsc(UUID employeeId);
}

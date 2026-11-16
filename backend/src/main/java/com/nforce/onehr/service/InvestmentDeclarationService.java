package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.InvestmentDeclarationResponse;
import com.nforce.onehr.dto.payroll.UpsertInvestmentDeclarationRequest;
import com.nforce.onehr.entity.InvestmentDeclaration;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.InvestmentDeclarationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/** Keka restyle (payroll module) — "Import Investment Declaration" (§3.4) admin CRUD. See
 * InvestmentDeclaration's own doc comment for why this is bookkeeping only. */
@Service
@RequiredArgsConstructor
public class InvestmentDeclarationService {

    private final InvestmentDeclarationRepository investmentDeclarationRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<InvestmentDeclarationResponse> list(UUID employeeId) {
        return investmentDeclarationRepository.findByEmployeeIdOrderByFinancialYearDescSectionAsc(employeeId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public InvestmentDeclarationResponse upsert(UpsertInvestmentDeclarationRequest req) {
        if (!employeeRepository.existsById(req.getEmployeeId())) {
            throw new NoSuchElementException("Employee not found: " + req.getEmployeeId());
        }
        String financialYear = req.getFinancialYear().trim();
        String section = req.getSection().trim();

        InvestmentDeclaration declaration = investmentDeclarationRepository
                .findByEmployeeIdAndFinancialYearAndSection(req.getEmployeeId(), financialYear, section)
                .orElseGet(() -> InvestmentDeclaration.builder()
                        .employeeId(req.getEmployeeId()).financialYear(financialYear).section(section).build());
        declaration.setDeclaredAmount(req.getDeclaredAmount());

        return toResponse(investmentDeclarationRepository.save(declaration));
    }

    private InvestmentDeclarationResponse toResponse(InvestmentDeclaration d) {
        return InvestmentDeclarationResponse.builder()
                .id(d.getId())
                .employeeId(d.getEmployeeId())
                .financialYear(d.getFinancialYear())
                .section(d.getSection())
                .declaredAmount(d.getDeclaredAmount())
                .build();
    }
}

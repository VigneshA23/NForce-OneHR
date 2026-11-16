package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.InvestmentDeclarationResponse;
import com.nforce.onehr.dto.payroll.UpsertInvestmentDeclarationRequest;
import com.nforce.onehr.entity.InvestmentDeclaration;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.InvestmentDeclarationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvestmentDeclarationServiceTest {

    @Mock private InvestmentDeclarationRepository investmentDeclarationRepository;
    @Mock private EmployeeRepository employeeRepository;

    @InjectMocks private InvestmentDeclarationService service;

    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(investmentDeclarationRepository.save(any(InvestmentDeclaration.class))).thenAnswer(inv -> {
            InvestmentDeclaration d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
    }

    private UpsertInvestmentDeclarationRequest req(String fy, String section, String amount) {
        UpsertInvestmentDeclarationRequest r = new UpsertInvestmentDeclarationRequest();
        r.setEmployeeId(employeeId);
        r.setFinancialYear(fy);
        r.setSection(section);
        r.setDeclaredAmount(new BigDecimal(amount));
        return r;
    }

    @Test
    void upsert_unknownEmployee_throwsNoSuchElement() {
        when(employeeRepository.existsById(employeeId)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> service.upsert(req("2026-27", "80C", "150000")));
        verify(investmentDeclarationRepository, never()).save(any());
    }

    @Test
    void upsert_newCombination_creates() {
        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        when(investmentDeclarationRepository.findByEmployeeIdAndFinancialYearAndSection(employeeId, "2026-27", "80C"))
                .thenReturn(Optional.empty());

        InvestmentDeclarationResponse resp = service.upsert(req("2026-27", "80C", "150000"));

        assertEquals(employeeId, resp.getEmployeeId());
        assertEquals("2026-27", resp.getFinancialYear());
        assertEquals("80C", resp.getSection());
        assertEquals(0, new BigDecimal("150000").compareTo(resp.getDeclaredAmount()));
    }

    @Test
    void upsert_existingCombination_updatesInPlaceRatherThanDuplicating() {
        InvestmentDeclaration existing = InvestmentDeclaration.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).financialYear("2026-27").section("80C")
                .declaredAmount(new BigDecimal("100000")).build();
        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        when(investmentDeclarationRepository.findByEmployeeIdAndFinancialYearAndSection(employeeId, "2026-27", "80C"))
                .thenReturn(Optional.of(existing));

        InvestmentDeclarationResponse resp = service.upsert(req("2026-27", "80C", "150000"));

        assertEquals(existing.getId(), resp.getId());
        assertEquals(0, new BigDecimal("150000").compareTo(resp.getDeclaredAmount()));
    }

    @Test
    void list_ordersByFinancialYearDescThenSection() {
        InvestmentDeclaration a = InvestmentDeclaration.builder().id(UUID.randomUUID()).employeeId(employeeId)
                .financialYear("2026-27").section("80C").declaredAmount(BigDecimal.TEN).build();
        when(investmentDeclarationRepository.findByEmployeeIdOrderByFinancialYearDescSectionAsc(employeeId)).thenReturn(List.of(a));

        List<InvestmentDeclarationResponse> result = service.list(employeeId);

        assertEquals(1, result.size());
        assertEquals("80C", result.get(0).getSection());
    }
}

package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.EmployeeCompensationResponse;
import com.nforce.onehr.dto.payroll.MyCtcBreakdownResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. The
 * resolution engine itself (SalaryStructureResolutionService) is used for real, not mocked, since
 * it's pure computation with no dependencies — same convention as PayrollRunServiceTest. */
@ExtendWith(MockitoExtension.class)
class MyFinancesServiceTest {

    @Mock private EmployeeCompensationRepository employeeCompensationRepository;
    @Mock private EmployeeCompensationService employeeCompensationService;
    @Mock private SalaryStructureComponentRepository salaryStructureComponentRepository;
    @Mock private UserRepository userRepository;

    private MyFinancesService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "employee@test.com";
    private final UUID structureId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MyFinancesService(employeeCompensationRepository, employeeCompensationService,
                salaryStructureComponentRepository, new SalaryStructureResolutionService(), userRepository);

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
    }

    private SalaryComponent catalogComponent(String name, String type) {
        return SalaryComponent.builder().id(UUID.randomUUID()).name(name).type(type).build();
    }

    private SalaryStructureComponent structureComponent(SalaryComponent catalog, String calcType, BigDecimal value, UUID referenceId) {
        return SalaryStructureComponent.builder().id(UUID.randomUUID()).component(catalog).calcType(calcType)
                .value(value).referenceStructureComponentId(referenceId).build();
    }

    @Test
    void getCurrentCtcBreakdown_realKekaStructure_resolvesAnnualAndMonthlyCorrectly() {
        SalaryComponent basicCatalog = catalogComponent("Basic", SalaryComponentType.EARNING);
        SalaryComponent hraCatalog = catalogComponent("HRA", SalaryComponentType.EARNING);
        SalaryComponent otherCatalog = catalogComponent("Other/Special Allowance", SalaryComponentType.EARNING);

        SalaryStructureComponent basic = structureComponent(basicCatalog, SalaryCalculationType.PERCENT_OF_GROSS, new BigDecimal("50"), null);
        SalaryStructureComponent hra = structureComponent(hraCatalog, SalaryCalculationType.PERCENT_OF_COMPONENT, new BigDecimal("40"), basic.getId());
        SalaryStructureComponent other = structureComponent(otherCatalog, SalaryCalculationType.BALANCING_REMAINDER, null, null);

        SalaryStructure structure = SalaryStructure.builder().id(structureId).name("Test Structure").build();
        EmployeeCompensation comp = EmployeeCompensation.builder().id(UUID.randomUUID()).employeeId(actorId)
                .structure(structure).ctc(new BigDecimal("1200000")).effectiveFrom(LocalDate.of(2026, 1, 1)).build();

        when(employeeCompensationRepository.findByEmployeeIdAndEffectiveToIsNull(actorId)).thenReturn(Optional.of(comp));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic, hra, other));

        MyCtcBreakdownResponse resp = service.getCurrentCtcBreakdown(actorEmail);

        assertEquals(structureId, resp.getStructureId());
        assertEquals(0, new BigDecimal("1200000").compareTo(resp.getCtc()));
        Map<String, BigDecimal> annualByName = resp.getComponents().stream()
                .collect(java.util.stream.Collectors.toMap(c -> c.getComponentName(), c -> c.getAnnualAmount()));
        // Basic = 50% of 1,200,000 = 600,000; HRA = 40% of Basic = 240,000; Other = remainder = 360,000.
        assertEquals(0, new BigDecimal("600000.00").compareTo(annualByName.get("Basic")));
        assertEquals(0, new BigDecimal("240000.00").compareTo(annualByName.get("HRA")));
        assertEquals(0, new BigDecimal("360000.00").compareTo(annualByName.get("Other/Special Allowance")));

        Map<String, BigDecimal> monthlyByName = resp.getComponents().stream()
                .collect(java.util.stream.Collectors.toMap(c -> c.getComponentName(), c -> c.getMonthlyAmount()));
        assertEquals(0, new BigDecimal("50000.00").compareTo(monthlyByName.get("Basic")));
        assertEquals(0, new BigDecimal("20000.00").compareTo(monthlyByName.get("HRA")));
        assertEquals(0, new BigDecimal("30000.00").compareTo(monthlyByName.get("Other/Special Allowance")));
    }

    @Test
    void getCurrentCtcBreakdown_noCurrentCompensation_rejected() {
        when(employeeCompensationRepository.findByEmployeeIdAndEffectiveToIsNull(actorId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.getCurrentCtcBreakdown(actorEmail));
    }

    @Test
    void getCurrentCtcBreakdown_actorNotFound_rejected() {
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.getCurrentCtcBreakdown("unknown@test.com"));
    }

    @Test
    void getCompensationHistory_delegatesWithOwnEmployeeId() {
        EmployeeCompensationResponse historyEntry = EmployeeCompensationResponse.builder()
                .id(UUID.randomUUID()).employeeId(actorId).structureId(structureId).build();
        when(employeeCompensationService.getHistory(actorId)).thenReturn(List.of(historyEntry));

        List<EmployeeCompensationResponse> result = service.getCompensationHistory(actorEmail);

        assertEquals(1, result.size());
        assertEquals(actorId, result.get(0).getEmployeeId());
        verify(employeeCompensationService).getHistory(actorId);
    }
}

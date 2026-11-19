package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.StatutoryPreviewResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. Covers only
 *  previewComputedAmount (the Statutory Override form's "what would this normally be" reference) —
 *  createOrOverride/clear/listForEmployee are exercised indirectly via StatutoryOverrideController
 *  and live functional testing, not duplicated here. */
@ExtendWith(MockitoExtension.class)
class StatutoryOverrideServiceTest {

    @Mock private StatutoryDeductionOverrideRepository statutoryDeductionOverrideRepository;
    @Mock private PayGroupRepository payGroupRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeCompensationRepository employeeCompensationRepository;
    @Mock private SalaryStructureComponentRepository salaryStructureComponentRepository;
    @Mock private StatutoryDeductionService statutoryDeductionService;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private StatutoryOverrideService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID payGroupId = UUID.randomUUID();
    private final UUID structureId = UUID.randomUUID();
    private final LocalDate referenceDate = LocalDate.of(2026, 6, 15);
    private final LocalDate cycleEnd = LocalDate.of(2026, 6, 28);

    @BeforeEach
    void setUp() {
        service = new StatutoryOverrideService(statutoryDeductionOverrideRepository, payGroupRepository,
                employeeRepository, employeeCompensationRepository, salaryStructureComponentRepository,
                new SalaryStructureResolutionService(), statutoryDeductionService, userRepository,
                auditService, auditSnapshot);

        PayGroup payGroup = PayGroup.builder().id(payGroupId).cycleStartDay(1).cycleEndDay(28).build();
        lenient().when(payGroupRepository.findById(payGroupId)).thenReturn(Optional.of(payGroup));
    }

    private Employee employeeWithCountry(String country) {
        Location location = country == null ? null : Location.builder().id(UUID.randomUUID()).country(country).build();
        return Employee.builder().userId(employeeId).location(location).build();
    }

    private EmployeeCompensation compensation(BigDecimal ctc) {
        SalaryStructure structure = SalaryStructure.builder().id(structureId).name("Test Structure").build();
        return EmployeeCompensation.builder().id(UUID.randomUUID()).employeeId(employeeId).structure(structure)
                .ctc(ctc).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
    }

    @Test
    void previewComputedAmount_noLocation_returnsNoteNotAmount() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employeeWithCountry(null)));

        StatutoryPreviewResponse resp = service.previewComputedAmount(employeeId, "PF", payGroupId, referenceDate);

        assertNull(resp.getComputedAmount());
        assertTrue(resp.getNote().toLowerCase().contains("location"));
        verifyNoInteractions(employeeCompensationRepository, statutoryDeductionService);
    }

    @Test
    void previewComputedAmount_noCompensationEffective_returnsNoteNotAmount() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employeeWithCountry("India")));
        when(employeeCompensationRepository.findByEmployeeIdAndEffectiveAt(employeeId, cycleEnd)).thenReturn(Optional.empty());

        StatutoryPreviewResponse resp = service.previewComputedAmount(employeeId, "PF", payGroupId, referenceDate);

        assertNull(resp.getComputedAmount());
        assertTrue(resp.getNote().toLowerCase().contains("compensation"));
        verifyNoInteractions(statutoryDeductionService);
    }

    @Test
    void previewComputedAmount_pfConfigured_returnsFormulaAmount_ignoringTheOverrideCheck() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employeeWithCountry("India")));
        EmployeeCompensation comp = compensation(new BigDecimal("1200000"));
        when(employeeCompensationRepository.findByEmployeeIdAndEffectiveAt(employeeId, cycleEnd)).thenReturn(Optional.of(comp));

        SalaryComponent basicCatalog = SalaryComponent.builder().id(UUID.randomUUID()).name("Basic")
                .type(SalaryComponentType.EARNING).prorateOnLop(true).build();
        SalaryStructureComponent basic = SalaryStructureComponent.builder().id(UUID.randomUUID()).component(basicCatalog)
                .calcType(SalaryCalculationType.PERCENT_OF_GROSS).value(new BigDecimal("50")).build();
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic));

        when(statutoryDeductionService.previewFormulaAmount(eq("India"), eq("PF"), any(), any(), eq(cycleEnd)))
                .thenReturn(Optional.of(new BigDecimal("2286.96")));

        StatutoryPreviewResponse resp = service.previewComputedAmount(employeeId, "PF", payGroupId, referenceDate);

        assertEquals(0, new BigDecimal("2286.96").compareTo(resp.getComputedAmount()));
        assertNull(resp.getNote());
        // Basic = 50% of Gross(CTC) = 600,000/yr = 50,000/mo; it's also the only EARNING component,
        // so gross pay (sum of EARNING components) is the same 50,000/mo.
        verify(statutoryDeductionService).previewFormulaAmount(eq("India"), eq("PF"),
                eq(new BigDecimal("50000.00")), eq(new BigDecimal("50000.00")), eq(cycleEnd));
    }

    @Test
    void previewComputedAmount_notConfigured_returnsNoteNotAmount() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employeeWithCountry("India")));
        EmployeeCompensation comp = compensation(new BigDecimal("1200000"));
        when(employeeCompensationRepository.findByEmployeeIdAndEffectiveAt(employeeId, cycleEnd)).thenReturn(Optional.of(comp));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of());

        when(statutoryDeductionService.previewFormulaAmount(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        StatutoryPreviewResponse resp = service.previewComputedAmount(employeeId, "ESI", payGroupId, referenceDate);

        assertNull(resp.getComputedAmount());
        assertNotNull(resp.getNote());
    }
}

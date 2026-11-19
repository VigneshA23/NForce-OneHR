package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.AssignCompensationRequest;
import com.nforce.onehr.dto.payroll.CurrentSalaryStructureRowResponse;
import com.nforce.onehr.dto.payroll.EmployeeCompensationResponse;
import com.nforce.onehr.dto.payroll.MyCtcComponentResponse;
import com.nforce.onehr.entity.EmployeeCompensation;
import com.nforce.onehr.entity.SalaryCalculationType;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.SalaryStructure;
import com.nforce.onehr.entity.SalaryStructureComponent;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import com.nforce.onehr.repository.SalaryStructureRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as PayGroupServiceTest/SalaryStructureServiceTest. */
@ExtendWith(MockitoExtension.class)
class EmployeeCompensationServiceTest {

    @Mock private EmployeeCompensationRepository employeeCompensationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PayGroupRepository payGroupRepository;
    @Mock private SalaryStructureRepository salaryStructureRepository;
    @Mock private SalaryStructureComponentRepository salaryStructureComponentRepository;
    @Mock private PayslipRepository payslipRepository;
    @Mock private PayrollRunService payrollRunService;
    @Mock private ArrearsService arrearsService;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private EmployeeCompensationService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID employeeId = UUID.randomUUID();
    private final UUID structureId = UUID.randomUUID();
    private final UUID payGroupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Same convention as MyFinancesServiceTest: SalaryStructureResolutionService is pure
        // computation with no dependencies, so it's used for real here rather than mocked.
        service = new EmployeeCompensationService(employeeCompensationRepository, employeeRepository,
                payGroupRepository, salaryStructureRepository, salaryStructureComponentRepository,
                payslipRepository, payrollRunService, arrearsService, userRepository, auditService,
                auditSnapshot, new SalaryStructureResolutionService());

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        lenient().when(employeeCompensationRepository.save(any(EmployeeCompensation.class))).thenAnswer(inv -> {
            EmployeeCompensation c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        // Arrears trigger 2 default: no already-closed cycles for this employee — individual tests
        // exercising the backdated-revision trigger override this.
        lenient().when(payslipRepository.findVisibleForEmployee(any(), any())).thenReturn(List.of());
    }

    private SalaryStructure structure() {
        return SalaryStructure.builder().id(structureId).name("L2 Structure").build();
    }

    private AssignCompensationRequest req(LocalDate effectiveFrom, String ctc) {
        AssignCompensationRequest r = new AssignCompensationRequest();
        r.setEmployeeId(employeeId);
        r.setPayGroupId(payGroupId);
        r.setStructureId(structureId);
        r.setCtc(new BigDecimal(ctc));
        r.setEffectiveFrom(effectiveFrom);
        return r;
    }

    @Test
    void assign_firstTime_persistsWithNoCurrentRowToClose() {
        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        when(salaryStructureRepository.findById(structureId)).thenReturn(Optional.of(structure()));
        when(salaryStructureComponentRepository.existsByStructureId(structureId)).thenReturn(true);
        when(employeeCompensationRepository.findByEmployeeIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        EmployeeCompensationResponse resp = service.assign(req(LocalDate.of(2026, 1, 1), "1200000"), actorEmail);

        assertEquals(employeeId, resp.getEmployeeId());
        assertEquals(payGroupId, resp.getPayGroupId());
        assertEquals(structureId, resp.getStructureId());
        assertEquals("L2 Structure", resp.getStructureName());
        assertEquals(0, new BigDecimal("1200000").compareTo(resp.getCtc()));
        assertNull(resp.getEffectiveTo());
        verify(employeeCompensationRepository, never()).closeCurrentEntry(any(), any());
        verify(auditService).log(eq(actorId), eq("EMPLOYEE_COMPENSATION_ASSIGNED"), any(), isNull(), any());
    }

    @Test
    void assign_revision_closesThePriorCurrentRow() {
        EmployeeCompensation existing = EmployeeCompensation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).structure(structure())
                .ctc(new BigDecimal("1000000")).effectiveFrom(LocalDate.of(2025, 1, 1)).build();

        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        when(salaryStructureRepository.findById(structureId)).thenReturn(Optional.of(structure()));
        when(salaryStructureComponentRepository.existsByStructureId(structureId)).thenReturn(true);
        when(employeeCompensationRepository.findByEmployeeIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.of(existing));

        LocalDate newEffectiveFrom = LocalDate.of(2026, 4, 1);
        EmployeeCompensationResponse resp = service.assign(req(newEffectiveFrom, "1500000"), actorEmail);

        verify(employeeCompensationRepository).closeCurrentEntry(employeeId, newEffectiveFrom);
        verify(auditService).log(eq(actorId), eq("EMPLOYEE_COMPENSATION_ASSIGNED"), any(), any(), any());
        assertEquals(0, new BigDecimal("1500000").compareTo(resp.getCtc()));
    }

    @Test
    void assign_effectiveFromNotAfterCurrent_rejected() {
        EmployeeCompensation existing = EmployeeCompensation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).structure(structure())
                .ctc(new BigDecimal("1000000")).effectiveFrom(LocalDate.of(2026, 1, 1)).build();

        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        when(salaryStructureRepository.findById(structureId)).thenReturn(Optional.of(structure()));
        when(salaryStructureComponentRepository.existsByStructureId(structureId)).thenReturn(true);
        when(employeeCompensationRepository.findByEmployeeIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> service.assign(req(LocalDate.of(2026, 1, 1), "1500000"), actorEmail));
        verify(employeeCompensationRepository, never()).save(any());
    }

    @Test
    void assign_unknownEmployee_rejected() {
        when(employeeRepository.existsById(employeeId)).thenReturn(false);

        assertThrows(java.util.NoSuchElementException.class,
                () -> service.assign(req(LocalDate.of(2026, 1, 1), "1500000"), actorEmail));
    }

    @Test
    void assign_unknownPayGroup_rejected() {
        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        when(payGroupRepository.existsById(payGroupId)).thenReturn(false);

        assertThrows(java.util.NoSuchElementException.class,
                () -> service.assign(req(LocalDate.of(2026, 1, 1), "1500000"), actorEmail));
        verify(salaryStructureRepository, never()).findById(any());
    }

    @Test
    void assign_unknownStructure_rejected() {
        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        when(salaryStructureRepository.findById(structureId)).thenReturn(Optional.empty());

        assertThrows(java.util.NoSuchElementException.class,
                () -> service.assign(req(LocalDate.of(2026, 1, 1), "1500000"), actorEmail));
    }

    @Test
    void assign_structureWithNoComponents_rejected() {
        when(employeeRepository.existsById(employeeId)).thenReturn(true);
        when(salaryStructureRepository.findById(structureId)).thenReturn(Optional.of(structure()));
        when(salaryStructureComponentRepository.existsByStructureId(structureId)).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> service.assign(req(LocalDate.of(2026, 1, 1), "1500000"), actorEmail));
    }

    @Test
    void getCurrent_unknownEmployee_throwsNoSuchElement() {
        when(employeeCompensationRepository.findByEmployeeIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        assertThrows(java.util.NoSuchElementException.class, () -> service.getCurrent(employeeId));
    }

    @Test
    void getHistory_ordersByRepositoryQuery() {
        EmployeeCompensation old = EmployeeCompensation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).structure(structure())
                .ctc(new BigDecimal("1000000")).effectiveFrom(LocalDate.of(2025, 1, 1))
                .effectiveTo(LocalDate.of(2026, 1, 1)).build();
        EmployeeCompensation current = EmployeeCompensation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).structure(structure())
                .ctc(new BigDecimal("1500000")).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        when(employeeCompensationRepository.findByEmployeeIdOrderByEffectiveFromDesc(employeeId))
                .thenReturn(List.of(current, old));

        List<EmployeeCompensationResponse> result = service.getHistory(employeeId);

        assertEquals(2, result.size());
        assertNull(result.get(0).getEffectiveTo());
        assertEquals(LocalDate.of(2026, 1, 1), result.get(1).getEffectiveTo());
    }

    @Test
    void listAllCurrent_delegatesToFindAllEffectiveAt() {
        LocalDate asOf = LocalDate.of(2026, 11, 16);
        EmployeeCompensation current = EmployeeCompensation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).structure(structure())
                .ctc(new BigDecimal("1500000")).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        when(employeeCompensationRepository.findAllEffectiveAt(asOf)).thenReturn(List.of(current));

        List<EmployeeCompensationResponse> result = service.listAllCurrent(asOf);

        assertEquals(1, result.size());
        assertEquals(employeeId, result.get(0).getEmployeeId());
    }

    @Test
    void listAllHistory_returnsEveryEmployeesRowsFromTheRepository() {
        UUID otherEmployeeId = UUID.randomUUID();
        EmployeeCompensation mine = EmployeeCompensation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).structure(structure())
                .ctc(new BigDecimal("1500000")).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        EmployeeCompensation theirs = EmployeeCompensation.builder()
                .id(UUID.randomUUID()).employeeId(otherEmployeeId).structure(structure())
                .ctc(new BigDecimal("900000")).effectiveFrom(LocalDate.of(2026, 2, 1)).build();
        when(employeeCompensationRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(theirs, mine));

        List<EmployeeCompensationResponse> result = service.listAllHistory();

        assertEquals(2, result.size());
        assertEquals(otherEmployeeId, result.get(0).getEmployeeId());
        assertEquals(employeeId, result.get(1).getEmployeeId());
    }

    @Test
    void listAllBetween_delegatesToFindAllEffectiveBetween() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        EmployeeCompensation row = EmployeeCompensation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).structure(structure())
                .ctc(new BigDecimal("1500000")).effectiveFrom(LocalDate.of(2026, 4, 1)).build();
        when(employeeCompensationRepository.findAllEffectiveBetween(start, end)).thenReturn(List.of(row));

        List<EmployeeCompensationResponse> result = service.listAllBetween(start, end);

        assertEquals(1, result.size());
        assertEquals(employeeId, result.get(0).getEmployeeId());
    }

    @Test
    void listAllCurrentBreakdown_resolvesEachRowsComponentsFromItsOwnStructure() {
        LocalDate asOf = LocalDate.of(2026, 11, 18);
        SalaryComponent basicCatalog = SalaryComponent.builder().id(UUID.randomUUID()).name("Basic").type(SalaryComponentType.EARNING).build();
        SalaryStructureComponent basic = SalaryStructureComponent.builder().id(UUID.randomUUID())
                .component(basicCatalog).calcType(SalaryCalculationType.PERCENT_OF_GROSS).value(new BigDecimal("50")).build();

        EmployeeCompensation row = EmployeeCompensation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).structure(structure())
                .ctc(new BigDecimal("1200000")).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        when(employeeCompensationRepository.findAllEffectiveAt(asOf)).thenReturn(List.of(row));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic));

        List<CurrentSalaryStructureRowResponse> result = service.listAllCurrentBreakdown(asOf);

        assertEquals(1, result.size());
        CurrentSalaryStructureRowResponse resp = result.get(0);
        assertEquals(employeeId, resp.getEmployeeId());
        assertEquals(structureId, resp.getStructureId());
        Map<String, BigDecimal> annualByName = resp.getComponents().stream()
                .collect(Collectors.toMap(MyCtcComponentResponse::getComponentName, MyCtcComponentResponse::getAnnualAmount));
        assertEquals(0, new BigDecimal("600000.00").compareTo(annualByName.get("Basic")));
    }
}

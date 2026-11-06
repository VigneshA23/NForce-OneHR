package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.AssignCompensationRequest;
import com.nforce.onehr.dto.payroll.EmployeeCompensationResponse;
import com.nforce.onehr.entity.EmployeeCompensation;
import com.nforce.onehr.entity.SalaryStructure;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import com.nforce.onehr.repository.SalaryStructureRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

/** Pure Mockito unit tests — same approach as PayGroupServiceTest/SalaryStructureServiceTest. */
@ExtendWith(MockitoExtension.class)
class EmployeeCompensationServiceTest {

    @Mock private EmployeeCompensationRepository employeeCompensationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private SalaryStructureRepository salaryStructureRepository;
    @Mock private SalaryStructureComponentRepository salaryStructureComponentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private EmployeeCompensationService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID employeeId = UUID.randomUUID();
    private final UUID structureId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(employeeCompensationRepository.save(any(EmployeeCompensation.class))).thenAnswer(inv -> {
            EmployeeCompensation c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
    }

    private SalaryStructure structure() {
        return SalaryStructure.builder().id(structureId).name("L2 Structure").build();
    }

    private AssignCompensationRequest req(LocalDate effectiveFrom, String ctc) {
        AssignCompensationRequest r = new AssignCompensationRequest();
        r.setEmployeeId(employeeId);
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
}

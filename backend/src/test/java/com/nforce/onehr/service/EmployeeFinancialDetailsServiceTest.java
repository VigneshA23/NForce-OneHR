package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.EmployeeFinancialDetailsResponse;
import com.nforce.onehr.dto.payroll.UpdateEmployeeFinancialDetailsRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeFinancialDetailsServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private EmployeeFinancialDetailsService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";

    @BeforeEach
    void setUp() {
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Employee employee() {
        Employee e = new Employee();
        e.setUserId(employeeId);
        e.setEmployeeCode("NF-001");
        e.setFullName("Asha Rao");
        return e;
    }

    @Test
    void get_unknownEmployee_throwsNoSuchElement() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.get(employeeId));
    }

    @Test
    void update_setsOnlyProvidedFields() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee()));
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(User.builder().id(actorId).email(actorEmail).build()));

        UpdateEmployeeFinancialDetailsRequest req = new UpdateEmployeeFinancialDetailsRequest();
        req.setPanNumber(" abcde1234f ");
        req.setUanNumber("100200300400");

        EmployeeFinancialDetailsResponse resp = service.update(employeeId, req, actorEmail);

        assertEquals("ABCDE1234F", resp.getPanNumber());
        assertEquals("100200300400", resp.getUanNumber());
        assertNull(resp.getEsiNumber());
        assertEquals("NF-001", resp.getEmployeeCode());
        verify(auditService).log(eq(actorId), eq("EMPLOYEE_FINANCIAL_DETAILS_UPDATED"), eq(employeeId), any(), any());
    }

    @Test
    void update_unknownEmployee_throwsNoSuchElement_andNeverLogs() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.update(employeeId, new UpdateEmployeeFinancialDetailsRequest(), actorEmail));
        verifyNoInteractions(auditService);
    }

    @Test
    void listAll_returnsEveryEmployeeFromTheRepository() {
        Employee other = new Employee();
        other.setUserId(UUID.randomUUID());
        other.setEmployeeCode("NF-002");
        other.setFullName("Ravi Kumar");
        when(employeeRepository.findAll()).thenReturn(List.of(employee(), other));

        List<EmployeeFinancialDetailsResponse> result = service.listAll();

        assertEquals(2, result.size());
        assertEquals("NF-001", result.get(0).getEmployeeCode());
        assertEquals("NF-002", result.get(1).getEmployeeCode());
    }

    @Test
    void update_leavesFieldsUntouchedWhenRequestOmitsThem() {
        Employee existing = employee();
        existing.setBankName("HDFC Bank");
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(existing));
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(User.builder().id(actorId).email(actorEmail).build()));

        UpdateEmployeeFinancialDetailsRequest req = new UpdateEmployeeFinancialDetailsRequest();
        req.setPanNumber("ABCDE1234F");

        EmployeeFinancialDetailsResponse resp = service.update(employeeId, req, actorEmail);

        assertEquals("HDFC Bank", resp.getBankName());
        assertEquals("ABCDE1234F", resp.getPanNumber());
    }
}

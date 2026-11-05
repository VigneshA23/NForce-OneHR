package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateSalaryComponentCatalogRequest;
import com.nforce.onehr.dto.payroll.SalaryComponentCatalogResponse;
import com.nforce.onehr.dto.payroll.UpdateSalaryComponentCatalogRequest;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.SalaryComponentRepository;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as LeaveTypeServiceTest. */
@ExtendWith(MockitoExtension.class)
class SalaryComponentServiceTest {

    @Mock private SalaryComponentRepository salaryComponentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private SalaryComponentService salaryComponentService;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(salaryComponentRepository.save(any(SalaryComponent.class))).thenAnswer(inv -> {
            SalaryComponent c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
    }

    private CreateSalaryComponentCatalogRequest createReq(String name, String type) {
        CreateSalaryComponentCatalogRequest req = new CreateSalaryComponentCatalogRequest();
        req.setName(name);
        req.setType(type);
        return req;
    }

    @Test
    void create_persistsAndReturnsIt() {
        SalaryComponentCatalogResponse resp = salaryComponentService.create(createReq("Shift Allowance", "AD_HOC_EARNING"), actorEmail);

        assertEquals("Shift Allowance", resp.getName());
        assertEquals("AD_HOC_EARNING", resp.getType());
        assertTrue(resp.isActive());
        verify(auditService).log(eq(actorId), eq("SALARY_COMPONENT_CREATED"), any(), isNull(), any());
    }

    @Test
    void create_duplicateName_isRejected() {
        when(salaryComponentRepository.existsByNameIgnoreCase("Basic")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryComponentService.create(createReq("Basic", "EARNING"), actorEmail));
        assertTrue(ex.getMessage().contains("Basic"));
        verify(salaryComponentRepository, never()).save(any());
    }

    @Test
    void create_trimsName() {
        SalaryComponentCatalogResponse resp = salaryComponentService.create(createReq("  Basic  ", "EARNING"), actorEmail);

        assertEquals("Basic", resp.getName());
    }

    @Test
    void update_deactivates_andIsPersisted() {
        SalaryComponent existing = SalaryComponent.builder().id(UUID.randomUUID()).name("Leave Encashment")
                .type("AD_HOC_EARNING").active(true).build();
        when(salaryComponentRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        UpdateSalaryComponentCatalogRequest req = new UpdateSalaryComponentCatalogRequest();
        req.setActive(false);
        SalaryComponentCatalogResponse resp = salaryComponentService.update(existing.getId(), req, actorEmail);

        assertFalse(resp.isActive());
        verify(auditService).log(eq(actorId), eq("SALARY_COMPONENT_UPDATED"), eq(existing.getId()), any(), any());
    }

    @Test
    void update_withOnlyOneField_leavesOthersUnchanged() {
        SalaryComponent existing = SalaryComponent.builder().id(UUID.randomUUID()).name("HRA")
                .type("EARNING").taxable(true).includedInCtc(true).shownOnPayslip(true).prorateOnLop(true).active(true).build();
        when(salaryComponentRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        UpdateSalaryComponentCatalogRequest req = new UpdateSalaryComponentCatalogRequest();
        req.setTaxSection("10(13A)");
        SalaryComponentCatalogResponse resp = salaryComponentService.update(existing.getId(), req, actorEmail);

        assertEquals("10(13A)", resp.getTaxSection());
        assertEquals("HRA", resp.getName());
        assertTrue(resp.isTaxable());
        assertTrue(resp.isActive());
    }

    @Test
    void update_toADuplicateName_isRejected() {
        SalaryComponent existing = SalaryComponent.builder().id(UUID.randomUUID()).name("HRA").type("EARNING").build();
        SalaryComponent other = SalaryComponent.builder().id(UUID.randomUUID()).name("Basic").type("EARNING").build();
        when(salaryComponentRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(salaryComponentRepository.findByNameIgnoreCase("Basic")).thenReturn(Optional.of(other));

        UpdateSalaryComponentCatalogRequest req = new UpdateSalaryComponentCatalogRequest();
        req.setName("Basic");

        assertThrows(IllegalArgumentException.class, () -> salaryComponentService.update(existing.getId(), req, actorEmail));
    }

    @Test
    void update_toItsOwnUnchangedName_isAllowed() {
        SalaryComponent existing = SalaryComponent.builder().id(UUID.randomUUID()).name("HRA").type("EARNING").build();
        when(salaryComponentRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(salaryComponentRepository.findByNameIgnoreCase("HRA")).thenReturn(Optional.of(existing));

        UpdateSalaryComponentCatalogRequest req = new UpdateSalaryComponentCatalogRequest();
        req.setName("HRA");

        SalaryComponentCatalogResponse resp = salaryComponentService.update(existing.getId(), req, actorEmail);
        assertEquals("HRA", resp.getName());
    }

    @Test
    void update_unknownId_throwsNoSuchElement() {
        UUID missing = UUID.randomUUID();
        when(salaryComponentRepository.findById(missing)).thenReturn(Optional.empty());

        UpdateSalaryComponentCatalogRequest req = new UpdateSalaryComponentCatalogRequest();
        req.setActive(false);
        assertThrows(NoSuchElementException.class, () -> salaryComponentService.update(missing, req, actorEmail));
    }

    @Test
    void list_ordersByName() {
        SalaryComponent a = SalaryComponent.builder().id(UUID.randomUUID()).name("Basic").type("EARNING").build();
        SalaryComponent b = SalaryComponent.builder().id(UUID.randomUUID()).name("HRA").type("EARNING").build();
        when(salaryComponentRepository.findAllByOrderByNameAsc()).thenReturn(List.of(a, b));

        List<SalaryComponentCatalogResponse> result = salaryComponentService.list();

        assertEquals(2, result.size());
        assertEquals("Basic", result.get(0).getName());
    }
}

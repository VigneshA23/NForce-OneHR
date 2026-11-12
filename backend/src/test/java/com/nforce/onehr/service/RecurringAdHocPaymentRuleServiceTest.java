package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateRecurringAdHocPaymentRuleRequest;
import com.nforce.onehr.dto.payroll.RecurringAdHocPaymentRuleResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. */
@ExtendWith(MockitoExtension.class)
class RecurringAdHocPaymentRuleServiceTest {

    @Mock private RecurringAdHocPaymentRuleRepository recurringAdHocPaymentRuleRepository;
    @Mock private PayGroupRepository payGroupRepository;
    @Mock private SalaryComponentRepository salaryComponentRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private RecurringAdHocPaymentRuleService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID payGroupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RecurringAdHocPaymentRuleService(recurringAdHocPaymentRuleRepository, payGroupRepository,
                salaryComponentRepository, employeeRepository, userRepository, auditService, auditSnapshot);

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(employeeRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(payGroupRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(recurringAdHocPaymentRuleRepository.save(any(RecurringAdHocPaymentRule.class))).thenAnswer(inv -> {
            RecurringAdHocPaymentRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
    }

    private SalaryComponent adHocEarningCatalog() {
        return SalaryComponent.builder().id(UUID.randomUUID()).name("Food Allowance")
                .type(SalaryComponentType.AD_HOC_EARNING).active(true).build();
    }

    private CreateRecurringAdHocPaymentRuleRequest req() {
        CreateRecurringAdHocPaymentRuleRequest r = new CreateRecurringAdHocPaymentRuleRequest();
        r.setComponentName("Food Allowance");
        r.setAmount(new BigDecimal("1100.00"));
        r.setReason("Monthly food allowance");
        return r;
    }

    @Test
    void create_validAdHocEarning_succeeds() {
        when(salaryComponentRepository.findByNameIgnoreCase("Food Allowance")).thenReturn(Optional.of(adHocEarningCatalog()));

        RecurringAdHocPaymentRuleResponse resp = service.create(req(), actorEmail);

        assertEquals("Food Allowance", resp.getComponentName());
        assertTrue(resp.isActive());
        assertEquals("All Pay Groups", resp.getPayGroupName());
        verify(auditService).log(eq(actorId), eq("PAYROLL_RECURRING_ADHOC_RULE_CREATED"), any(), isNull(), any());
    }

    @Test
    void create_scopedToPayGroup_resolvesPayGroupName() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        when(salaryComponentRepository.findByNameIgnoreCase("Food Allowance")).thenReturn(Optional.of(adHocEarningCatalog()));
        CreateRecurringAdHocPaymentRuleRequest req = req();
        req.setPayGroupId(payGroupId);
        when(payGroupRepository.findAllById(any())).thenReturn(List.of(PayGroup.builder().id(payGroupId).name("Default pay group").build()));

        RecurringAdHocPaymentRuleResponse resp = service.create(req, actorEmail);

        assertEquals(payGroupId, resp.getPayGroupId());
        assertEquals("Default pay group", resp.getPayGroupName());
    }

    @Test
    void create_unknownPayGroup_rejected() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(false);
        CreateRecurringAdHocPaymentRuleRequest req = req();
        req.setPayGroupId(payGroupId);

        assertThrows(NoSuchElementException.class, () -> service.create(req, actorEmail));
    }

    @Test
    void create_unknownComponent_rejected() {
        when(salaryComponentRepository.findByNameIgnoreCase("Food Allowance")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.create(req(), actorEmail));
    }

    @Test
    void create_wrongComponentType_rejected() {
        SalaryComponent recurring = SalaryComponent.builder().id(UUID.randomUUID()).name("Basic")
                .type(SalaryComponentType.EARNING).active(true).build();
        when(salaryComponentRepository.findByNameIgnoreCase("Basic")).thenReturn(Optional.of(recurring));
        CreateRecurringAdHocPaymentRuleRequest req = req();
        req.setComponentName("Basic");

        assertThrows(IllegalArgumentException.class, () -> service.create(req, actorEmail));
    }

    @Test
    void setActive_deactivatesAndAudits() {
        UUID ruleId = UUID.randomUUID();
        RecurringAdHocPaymentRule rule = RecurringAdHocPaymentRule.builder().id(ruleId).componentName("Food Allowance")
                .componentType(SalaryComponentType.AD_HOC_EARNING).amount(BigDecimal.TEN).reason("x")
                .active(true).createdBy(actorId).build();
        when(recurringAdHocPaymentRuleRepository.findById(ruleId)).thenReturn(Optional.of(rule));
        when(recurringAdHocPaymentRuleRepository.save(any(RecurringAdHocPaymentRule.class))).thenAnswer(inv -> inv.getArgument(0));

        RecurringAdHocPaymentRuleResponse resp = service.setActive(ruleId, false, actorEmail);

        assertFalse(resp.isActive());
        verify(auditService).log(eq(actorId), eq("PAYROLL_RECURRING_ADHOC_RULE_DEACTIVATED"), eq(ruleId), any(), any());
    }

    @Test
    void setActive_unknownRule_rejected() {
        UUID ruleId = UUID.randomUUID();
        when(recurringAdHocPaymentRuleRepository.findById(ruleId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.setActive(ruleId, false, actorEmail));
    }

    @Test
    void list_returnsAllOrdered() {
        RecurringAdHocPaymentRule rule = RecurringAdHocPaymentRule.builder().id(UUID.randomUUID()).componentName("Food Allowance")
                .componentType(SalaryComponentType.AD_HOC_EARNING).amount(BigDecimal.TEN).reason("x")
                .active(true).createdBy(actorId).build();
        when(recurringAdHocPaymentRuleRepository.findAllOrdered()).thenReturn(List.of(rule));

        List<RecurringAdHocPaymentRuleResponse> result = service.list();

        assertEquals(1, result.size());
        assertEquals("Food Allowance", result.get(0).getComponentName());
    }
}

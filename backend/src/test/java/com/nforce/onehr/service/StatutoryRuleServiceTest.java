package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.payroll.CreateStatutoryRuleRequest;
import com.nforce.onehr.dto.payroll.StatutoryRuleResponse;
import com.nforce.onehr.entity.StatutoryRule;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.StatutoryRuleRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as EmployeeCompensationServiceTest. */
@ExtendWith(MockitoExtension.class)
class StatutoryRuleServiceTest {

    @Mock private StatutoryRuleRepository statutoryRuleRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private ObjectMapper objectMapper;
    private StatutoryRuleService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new StatutoryRuleService(statutoryRuleRepository, userRepository, objectMapper, auditService, auditSnapshot);

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(statutoryRuleRepository.save(any(StatutoryRule.class))).thenAnswer(inv -> {
            StatutoryRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
    }

    private CreateStatutoryRuleRequest req(String country, String ruleType, String slabConfig, LocalDate effectiveFrom) {
        CreateStatutoryRuleRequest r = new CreateStatutoryRuleRequest();
        r.setCountry(country);
        r.setRuleType(ruleType);
        r.setSlabConfig(slabConfig);
        r.setEffectiveFrom(effectiveFrom);
        return r;
    }

    @Test
    void create_firstTime_persistsUppercasedKeys_withNoPriorRowToClose() {
        when(statutoryRuleRepository.findByCountryAndRuleTypeAndEffectiveToIsNull("IN", "PF")).thenReturn(Optional.empty());

        StatutoryRuleResponse resp = service.createOrRevise(
                req("in", "pf", "{\"rate\":0.12}", LocalDate.of(2026, 1, 1)), actorEmail);

        assertEquals("IN", resp.getCountry());
        assertEquals("PF", resp.getRuleType());
        assertEquals("{\"rate\":0.12}", resp.getSlabConfig());
        assertNull(resp.getEffectiveTo());
        verify(statutoryRuleRepository, never()).closeCurrentEntry(any(), any(), any());
        verify(auditService).log(eq(actorId), eq("STATUTORY_RULE_CREATED"), any(), isNull(), any());
    }

    @Test
    void revise_closesThePriorCurrentRow() {
        StatutoryRule existing = StatutoryRule.builder().id(UUID.randomUUID()).country("IN").ruleType("PF")
                .slabConfig("{\"rate\":0.12}").effectiveFrom(LocalDate.of(2025, 1, 1)).build();
        when(statutoryRuleRepository.findByCountryAndRuleTypeAndEffectiveToIsNull("IN", "PF")).thenReturn(Optional.of(existing));

        LocalDate newEffectiveFrom = LocalDate.of(2026, 4, 1);
        service.createOrRevise(req("IN", "PF", "{\"rate\":0.13}", newEffectiveFrom), actorEmail);

        verify(statutoryRuleRepository).closeCurrentEntry("IN", "PF", newEffectiveFrom);
        verify(auditService).log(eq(actorId), eq("STATUTORY_RULE_CREATED"), any(), any(), any());
    }

    @Test
    void revise_effectiveFromNotAfterCurrent_rejected() {
        StatutoryRule existing = StatutoryRule.builder().id(UUID.randomUUID()).country("IN").ruleType("PF")
                .slabConfig("{}").effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        when(statutoryRuleRepository.findByCountryAndRuleTypeAndEffectiveToIsNull("IN", "PF")).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrRevise(req("IN", "PF", "{}", LocalDate.of(2026, 1, 1)), actorEmail));
        verify(statutoryRuleRepository, never()).save(any());
    }

    @Test
    void create_malformedJson_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createOrRevise(req("IN", "PF", "{not json", LocalDate.of(2026, 1, 1)), actorEmail));
        verify(statutoryRuleRepository, never()).save(any());
    }

    @Test
    void listCurrent_returnsRepositoryOrdering() {
        StatutoryRule r = StatutoryRule.builder().id(UUID.randomUUID()).country("IN").ruleType("PF")
                .slabConfig("{}").effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        when(statutoryRuleRepository.findByEffectiveToIsNullOrderByCountryAscRuleTypeAsc()).thenReturn(List.of(r));

        List<StatutoryRuleResponse> result = service.listCurrent();

        assertEquals(1, result.size());
        assertEquals("PF", result.get(0).getRuleType());
    }

    @Test
    void history_uppercasesLookupKeys() {
        when(statutoryRuleRepository.findByCountryAndRuleTypeOrderByEffectiveFromDesc("IN", "PF")).thenReturn(List.of());

        service.history("in", "pf");

        verify(statutoryRuleRepository).findByCountryAndRuleTypeOrderByEffectiveFromDesc("IN", "PF");
    }
}

package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.payroll.CreateStatutoryRuleRequest;
import com.nforce.onehr.dto.payroll.LocationCountryMismatchResponse;
import com.nforce.onehr.dto.payroll.StatutoryRuleResponse;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.StatutoryRule;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.repository.StatutoryRuleRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @Mock private LocationRepository locationRepository;
    @Mock private EmployeeRepository employeeRepository;

    private ObjectMapper objectMapper;
    private StatutoryRuleService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new StatutoryRuleService(statutoryRuleRepository, userRepository, objectMapper, auditService,
                auditSnapshot, locationRepository, employeeRepository);

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
    void create_firstTime_trimsCountry_uppercasesRuleType_withNoPriorRowToClose() {
        when(statutoryRuleRepository.findByCountryAndRuleTypeAndEffectiveToIsNull("India", "PF")).thenReturn(Optional.empty());

        StatutoryRuleResponse resp = service.createOrRevise(
                req(" India ", "pf", "{\"rate\":0.12}", LocalDate.of(2026, 1, 1)), actorEmail);

        // Country is case-sensitive and must match an employee's Location.country exactly, so
        // only whitespace is trimmed — unlike ruleType, it's never uppercased.
        assertEquals("India", resp.getCountry());
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
    void history_trimsCountry_uppercasesOnlyRuleType() {
        when(statutoryRuleRepository.findByCountryAndRuleTypeOrderByEffectiveFromDesc("India", "PF")).thenReturn(List.of());

        service.history(" India ", "pf");

        verify(statutoryRuleRepository).findByCountryAndRuleTypeOrderByEffectiveFromDesc("India", "PF");
    }

    private Location location(String name, String country, boolean active) {
        return Location.builder().id(UUID.randomUUID()).name(name).country(country).active(active).build();
    }

    @Test
    void findLocationCountryMismatches_noRulesConfiguredAtAll_returnsEmptyWithoutEvenCheckingLocations() {
        when(statutoryRuleRepository.findByEffectiveToIsNullOrderByCountryAscRuleTypeAsc()).thenReturn(List.of());

        assertEquals(List.of(), service.findLocationCountryMismatches());
        verifyNoInteractions(locationRepository, employeeRepository);
    }

    @Test
    void findLocationCountryMismatches_flagsLocationWhoseCountryMatchesNoConfiguredRule() {
        StatutoryRule pfRule = StatutoryRule.builder().id(UUID.randomUUID()).country("IN").ruleType("PF")
                .slabConfig("{}").effectiveFrom(LocalDate.of(2026, 9, 30)).build();
        when(statutoryRuleRepository.findByEffectiveToIsNullOrderByCountryAscRuleTypeAsc()).thenReturn(List.of(pfRule));

        Location hyderabad = location("Hyderabad", "India", true); // "India" != "IN" — mismatch
        Location bangalore = location("Bangalore", "IN", true); // matches — no mismatch
        Location empty = location("Chennai", null, true); // no employees — not flagged despite blank country
        when(locationRepository.findAll()).thenReturn(List.of(hyderabad, bangalore, empty));
        when(employeeRepository.countByLocationId(hyderabad.getId())).thenReturn(47L);
        when(employeeRepository.countByLocationId(bangalore.getId())).thenReturn(14L);
        when(employeeRepository.countByLocationId(empty.getId())).thenReturn(0L);

        List<LocationCountryMismatchResponse> result = service.findLocationCountryMismatches();

        assertEquals(1, result.size());
        assertEquals("Hyderabad", result.get(0).getLocationName());
        assertEquals("India", result.get(0).getCountry());
        assertEquals(47L, result.get(0).getEmployeeCount());
    }

    @Test
    void findLocationCountryMismatches_ignoresInactiveLocations() {
        StatutoryRule pfRule = StatutoryRule.builder().id(UUID.randomUUID()).country("IN").ruleType("PF")
                .slabConfig("{}").effectiveFrom(LocalDate.of(2026, 9, 30)).build();
        when(statutoryRuleRepository.findByEffectiveToIsNullOrderByCountryAscRuleTypeAsc()).thenReturn(List.of(pfRule));
        when(locationRepository.findAll()).thenReturn(List.of(location("Old Office", "India", false)));

        assertEquals(List.of(), service.findLocationCountryMismatches());
        verify(employeeRepository, never()).countByLocationId(any());
    }
}

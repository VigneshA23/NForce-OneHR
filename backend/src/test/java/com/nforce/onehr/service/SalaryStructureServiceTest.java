package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateSalaryStructureRequest;
import com.nforce.onehr.dto.payroll.SalaryComponentRequest;
import com.nforce.onehr.dto.payroll.SalaryStructureResponse;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryStructure;
import com.nforce.onehr.entity.SalaryStructureComponent;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.SalaryComponentRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as LeaveTypeServiceTest. */
@ExtendWith(MockitoExtension.class)
class SalaryStructureServiceTest {

    @Mock private SalaryStructureRepository salaryStructureRepository;
    @Mock private SalaryStructureComponentRepository salaryStructureComponentRepository;
    @Mock private SalaryComponentRepository salaryComponentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private SalaryStructureService salaryStructureService;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";

    // Every test's catalog, registered via catalog(...) — findByNameIgnoreCase answers from
    // this map, so a test only needs to declare the catalog entries it actually uses.
    private final Map<String, SalaryComponent> catalog = new HashMap<>();

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(salaryStructureRepository.save(any(SalaryStructure.class))).thenAnswer(inv -> {
            SalaryStructure s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        // saveAll assigns an id to any not-yet-persisted component and returns the same list —
        // close enough to real JPA behavior for these tests (order and identity preserved, which
        // is all the service relies on when resolving referenceComponentName afterward).
        lenient().when(salaryStructureComponentRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<SalaryStructureComponent> list = inv.getArgument(0);
            list.forEach(c -> { if (c.getId() == null) c.setId(UUID.randomUUID()); });
            return list;
        });
        lenient().when(salaryComponentRepository.findByNameIgnoreCase(any())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return Optional.ofNullable(catalog.get(name));
        });
    }

    /** Registers (or re-registers) a catalog entry so resolveCatalogComponents can find it. */
    private SalaryComponent catalog(String name, String type) {
        SalaryComponent c = SalaryComponent.builder().id(UUID.randomUUID()).name(name).type(type).active(true).build();
        catalog.put(name, c);
        return c;
    }

    private SalaryComponentRequest fixed(String componentName, String value) {
        SalaryComponentRequest req = new SalaryComponentRequest();
        req.setComponentName(componentName);
        req.setCalcType("FIXED");
        req.setValue(new BigDecimal(value));
        return req;
    }

    private SalaryComponentRequest percentOfGross(String componentName, String value) {
        SalaryComponentRequest req = new SalaryComponentRequest();
        req.setComponentName(componentName);
        req.setCalcType("PERCENT_OF_GROSS");
        req.setValue(new BigDecimal(value));
        return req;
    }

    private SalaryComponentRequest percentOfComponent(String componentName, String value, String referenceName) {
        SalaryComponentRequest req = new SalaryComponentRequest();
        req.setComponentName(componentName);
        req.setCalcType("PERCENT_OF_COMPONENT");
        req.setValue(new BigDecimal(value));
        req.setReferenceComponentName(referenceName);
        return req;
    }

    private SalaryComponentRequest balancingRemainder(String componentName) {
        SalaryComponentRequest req = new SalaryComponentRequest();
        req.setComponentName(componentName);
        req.setCalcType("BALANCING_REMAINDER");
        return req;
    }

    private CreateSalaryStructureRequest createReq(String name, String grade, SalaryComponentRequest... components) {
        CreateSalaryStructureRequest req = new CreateSalaryStructureRequest();
        req.setName(name);
        req.setGrade(grade);
        req.setComponents(List.of(components));
        return req;
    }

    @Test
    void create_kekaStyleStructure_resolvesReferencesAndPersists() {
        catalog("Basic", "EARNING");
        catalog("HRA", "EARNING");
        catalog("Travel", "EARNING");
        catalog("Conveyance", "EARNING");
        catalog("Other Allowance", "EARNING");

        CreateSalaryStructureRequest req = createReq("2026-27 Standard", "L2",
                percentOfGross("Basic", "50"),
                percentOfComponent("HRA", "40", "Basic"),
                percentOfComponent("Travel", "20", "Basic"),
                fixed("Conveyance", "1600"),
                balancingRemainder("Other Allowance"));
        req.setFinancialYear("2026-27");

        SalaryStructureResponse resp = salaryStructureService.create(req, actorEmail);

        assertEquals("2026-27", resp.getFinancialYear());
        assertEquals(5, resp.getComponents().size());
        var hra = resp.getComponents().stream().filter(c -> c.getComponentName().equals("HRA")).findFirst().orElseThrow();
        assertEquals("Basic", hra.getReferenceComponentName());
        assertEquals("EARNING", hra.getType());
        var other = resp.getComponents().stream().filter(c -> c.getComponentName().equals("Other Allowance")).findFirst().orElseThrow();
        assertNull(other.getValue());
        assertNull(other.getReferenceComponentName());
        verify(auditService).log(eq(actorId), eq("SALARY_STRUCTURE_CREATED"), any(), isNull(), any());
    }

    @Test
    void create_referenceResolvesRegardlessOfRequestOrder() {
        catalog("Basic", "EARNING");
        catalog("HRA", "EARNING");
        CreateSalaryStructureRequest req = createReq("Order Test", null,
                percentOfComponent("HRA", "40", "Basic"),
                percentOfGross("Basic", "50"));

        SalaryStructureResponse resp = salaryStructureService.create(req, actorEmail);

        var hra = resp.getComponents().stream().filter(c -> c.getComponentName().equals("HRA")).findFirst().orElseThrow();
        assertEquals("Basic", hra.getReferenceComponentName());
    }

    @Test
    void create_capAndUseLowerOfCap_arePersisted() {
        catalog("Basic", "EARNING");
        catalog("PF", "DEDUCTION");
        SalaryComponentRequest pf = percentOfComponent("PF", "12", "Basic");
        pf.setCap(new BigDecimal("3000"));
        pf.setUseLowerOfCap(true);
        CreateSalaryStructureRequest req = createReq("With Cap", null,
                percentOfGross("Basic", "50"), pf);

        SalaryStructureResponse resp = salaryStructureService.create(req, actorEmail);

        var pfResp = resp.getComponents().stream().filter(c -> c.getComponentName().equals("PF")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("3000"), pfResp.getCap());
        assertTrue(pfResp.isUseLowerOfCap());
    }

    @Test
    void create_catalogFlagsAreInlinedOntoTheResponse() {
        SalaryComponent basicCatalog = catalog("Basic", "EARNING");
        basicCatalog.setTaxable(false);
        basicCatalog.setIncludedInCtc(true);
        basicCatalog.setShownOnPayslip(true);
        basicCatalog.setProrateOnLop(false);
        CreateSalaryStructureRequest req = createReq("Flags Test", null, fixed("Basic", "50000"));

        SalaryStructureResponse resp = salaryStructureService.create(req, actorEmail);

        var basic = resp.getComponents().get(0);
        assertFalse(basic.isTaxable());
        assertTrue(basic.isIncludedInCtc());
        assertTrue(basic.isShownOnPayslip());
        assertFalse(basic.isProrateOnLop());
    }

    @Test
    void create_unknownCatalogComponent_isRejected() {
        CreateSalaryStructureRequest req = createReq("Bad", null, fixed("NoSuchComponent", "1"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("catalog"));
        verify(salaryStructureRepository, never()).save(any());
    }

    @Test
    void create_deactivatedCatalogComponent_isRejected() {
        SalaryComponent inactive = catalog("Old Allowance", "EARNING");
        inactive.setActive(false);
        CreateSalaryStructureRequest req = createReq("Bad", null, fixed("Old Allowance", "1"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("deactivated"));
    }

    @Test
    void create_balancingRemainderWithValue_isRejected() {
        catalog("Other", "EARNING");
        SalaryComponentRequest bad = balancingRemainder("Other");
        bad.setValue(new BigDecimal("100"));
        CreateSalaryStructureRequest req = createReq("Bad", null, bad);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("BALANCING_REMAINDER"));
        verify(salaryStructureRepository, never()).save(any());
    }

    @Test
    void create_nonBalancingRemainderWithoutValue_isRejected() {
        catalog("Basic", "EARNING");
        SalaryComponentRequest bad = fixed("Basic", "1");
        bad.setValue(null);
        CreateSalaryStructureRequest req = createReq("Bad", null, bad);

        assertThrows(IllegalArgumentException.class, () -> salaryStructureService.create(req, actorEmail));
    }

    @Test
    void create_percentOfComponentWithoutReferenceName_isRejected() {
        catalog("HRA", "EARNING");
        SalaryComponentRequest bad = percentOfComponent("HRA", "40", null);
        CreateSalaryStructureRequest req = createReq("Bad", null, bad);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("referenceComponentName"));
    }

    @Test
    void create_percentOfComponentReferencingItself_isRejected() {
        catalog("HRA", "EARNING");
        CreateSalaryStructureRequest req = createReq("Bad", null,
                percentOfComponent("HRA", "40", "HRA"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("itself"));
    }

    @Test
    void create_percentOfComponentReferencingUnknownName_isRejected() {
        catalog("HRA", "EARNING");
        CreateSalaryStructureRequest req = createReq("Bad", null,
                percentOfComponent("HRA", "40", "NoSuchComponent"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("does not match"));
    }

    @Test
    void create_referenceNameOnNonPercentOfComponent_isRejected() {
        catalog("Basic", "EARNING");
        catalog("Conveyance", "EARNING");
        SalaryComponentRequest bad = fixed("Conveyance", "1600");
        bad.setReferenceComponentName("Basic");
        CreateSalaryStructureRequest req = createReq("Bad", null,
                fixed("Basic", "1"), bad);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("only valid for PERCENT_OF_COMPONENT"));
    }

    @Test
    void create_useLowerOfCapWithoutCap_isRejected() {
        catalog("Basic", "EARNING");
        SalaryComponentRequest bad = fixed("Basic", "1");
        bad.setUseLowerOfCap(true);
        CreateSalaryStructureRequest req = createReq("Bad", null, bad);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("useLowerOfCap"));
    }

    @Test
    void create_duplicateComponentNames_isRejected() {
        catalog("Basic", "EARNING");
        CreateSalaryStructureRequest req = createReq("Bad", null,
                fixed("Basic", "1"), fixed("Basic", "2"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("Duplicate"));
    }

    @Test
    void create_twoBalancingRemainderComponents_isRejected() {
        catalog("Other1", "EARNING");
        catalog("Other2", "EARNING");
        CreateSalaryStructureRequest req = createReq("Bad", null,
                balancingRemainder("Other1"), balancingRemainder("Other2"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> salaryStructureService.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("at most one BALANCING_REMAINDER"));
    }

    @Test
    void list_resolvesReferenceComponentNamePerStructure() {
        SalaryStructure structure = SalaryStructure.builder().id(UUID.randomUUID()).name("A").build();
        SalaryComponent basicCatalog = SalaryComponent.builder().id(UUID.randomUUID()).name("Basic").type("EARNING").build();
        SalaryComponent hraCatalog = SalaryComponent.builder().id(UUID.randomUUID()).name("HRA").type("EARNING").build();
        SalaryStructureComponent basic = SalaryStructureComponent.builder().id(UUID.randomUUID()).structure(structure)
                .component(basicCatalog).calcType("PERCENT_OF_GROSS").value(new BigDecimal("50")).build();
        SalaryStructureComponent hra = SalaryStructureComponent.builder().id(UUID.randomUUID()).structure(structure)
                .component(hraCatalog).calcType("PERCENT_OF_COMPONENT").value(new BigDecimal("40"))
                .referenceStructureComponentId(basic.getId()).build();

        when(salaryStructureRepository.findAll()).thenReturn(List.of(structure));
        when(salaryStructureComponentRepository.findByStructureIdIn(List.of(structure.getId()))).thenReturn(List.of(basic, hra));

        List<SalaryStructureResponse> result = salaryStructureService.list();

        var hraResp = result.get(0).getComponents().stream().filter(c -> c.getComponentName().equals("HRA")).findFirst().orElseThrow();
        assertEquals("Basic", hraResp.getReferenceComponentName());
    }

    @Test
    void get_unknownId_throwsNoSuchElement() {
        UUID missing = UUID.randomUUID();
        when(salaryStructureRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> salaryStructureService.get(missing));
    }
}

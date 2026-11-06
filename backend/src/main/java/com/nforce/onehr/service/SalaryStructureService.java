package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateSalaryStructureRequest;
import com.nforce.onehr.dto.payroll.SalaryComponentRequest;
import com.nforce.onehr.dto.payroll.SalaryComponentResponse;
import com.nforce.onehr.dto.payroll.SalaryStructureResponse;
import com.nforce.onehr.entity.SalaryCalculationType;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryStructure;
import com.nforce.onehr.entity.SalaryStructureComponent;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.SalaryComponentRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import com.nforce.onehr.repository.SalaryStructureRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C01 (ONEHR-607) — Salary Structure template: a reusable set of pay components (Basic, HRA,
 * allowances, deductions) assigned to employees via US-C02's EmployeeCompensation, rather than
 * hardcoding pay rules per employee. Each component used is selected from the shared catalog
 * (US-C20, {@link SalaryComponent}) — this service never creates a catalog entry itself, only
 * {@link SalaryStructureComponent} rows that say how THIS structure computes an existing one.
 * CRUD-only here, same split as {@link LeaveTypeService} from its own read side — actually
 * resolving a structure's components into a payslip is a later story's concern, not this one's.
 */
@Service
@RequiredArgsConstructor
public class SalaryStructureService {

    private final SalaryStructureRepository salaryStructureRepository;
    private final SalaryStructureComponentRepository salaryStructureComponentRepository;
    private final SalaryComponentRepository salaryComponentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public SalaryStructureResponse create(CreateSalaryStructureRequest req, String actorEmail) {
        Map<String, SalaryComponent> catalogByName = resolveCatalogComponents(req.getComponents());
        validateComponents(req.getComponents(), catalogByName);

        final SalaryStructure savedStructure = salaryStructureRepository.save(SalaryStructure.builder()
                .name(req.getName().trim())
                .grade(req.getGrade() != null ? req.getGrade().trim() : null)
                .financialYear(req.getFinancialYear() != null ? req.getFinancialYear().trim() : null)
                .build());

        // Two passes, same reason as before the catalog existed: a PERCENT_OF_COMPONENT request
        // references a sibling by its catalog componentName, since that sibling has no
        // structure-component id yet when the request is submitted — save every line first to get
        // ids, then resolve names to ids and save again. Order-independent: HRA can list "Basic"
        // whether Basic appears before or after it in the request. A plain Java field (not a DB
        // CHECK constraint) enforces "reference set iff PERCENT_OF_COMPONENT" — see V250's own
        // header for why a CHECK here doesn't work with this two-phase save.
        List<SalaryStructureComponent> components = req.getComponents().stream()
                .map(c -> toStructureComponentEntity(c, savedStructure, catalogByName.get(c.getComponentName().trim())))
                .collect(Collectors.toList());
        components = salaryStructureComponentRepository.saveAll(components);

        Map<String, SalaryStructureComponent> byComponentName = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            byComponentName.put(req.getComponents().get(i).getComponentName().trim(), components.get(i));
        }
        boolean anyReferenceResolved = false;
        for (int i = 0; i < components.size(); i++) {
            SalaryComponentRequest reqComponent = req.getComponents().get(i);
            if (SalaryCalculationType.PERCENT_OF_COMPONENT.equals(reqComponent.getCalcType())) {
                SalaryStructureComponent target = byComponentName.get(reqComponent.getReferenceComponentName().trim());
                components.get(i).setReferenceStructureComponentId(target.getId());
                anyReferenceResolved = true;
            }
        }
        if (anyReferenceResolved) {
            components = salaryStructureComponentRepository.saveAll(components);
        }

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(structureSnapshot(savedStructure, components));
        auditService.log(actor.getId(), "SALARY_STRUCTURE_CREATED", savedStructure.getId(), null, after);

        return toResponse(savedStructure, components);
    }

    @Transactional(readOnly = true)
    public List<SalaryStructureResponse> list() {
        List<SalaryStructure> structures = salaryStructureRepository.findAll();
        if (structures.isEmpty()) return List.of();

        List<UUID> structureIds = structures.stream().map(SalaryStructure::getId).toList();
        Map<UUID, List<SalaryStructureComponent>> componentsByStructureId = salaryStructureComponentRepository
                .findByStructureIdIn(structureIds).stream()
                .collect(Collectors.groupingBy(c -> c.getStructure().getId(), LinkedHashMap::new, Collectors.toList()));

        return structures.stream()
                .map(s -> toResponse(s, componentsByStructureId.getOrDefault(s.getId(), List.of())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SalaryStructureResponse get(UUID id) {
        SalaryStructure structure = salaryStructureRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Salary structure not found: " + id));
        List<SalaryStructureComponent> components = salaryStructureComponentRepository.findByStructureId(id);
        return toResponse(structure, components);
    }

    /** Every componentName must resolve to an existing, active catalog entry. */
    private Map<String, SalaryComponent> resolveCatalogComponents(List<SalaryComponentRequest> components) {
        Map<String, SalaryComponent> byName = new HashMap<>();
        for (SalaryComponentRequest c : components) {
            String name = c.getComponentName().trim();
            if (byName.containsKey(name)) continue;
            SalaryComponent catalogEntry = salaryComponentRepository.findByNameIgnoreCase(name)
                    .orElseThrow(() -> new IllegalArgumentException("No salary component named '" + name + "' in the catalog"));
            if (!catalogEntry.isActive()) {
                throw new IllegalArgumentException("Salary component '" + name + "' is deactivated and can't be used in a new structure");
            }
            byName.put(name, catalogEntry);
        }
        return byName;
    }

    /**
     * Cross-field rules {@code @Valid} can't express on its own (see SalaryComponentRequest's
     * doc comment). Checked up front, before anything is saved, so a bad request never leaves a
     * partially-created structure behind.
     */
    private void validateComponents(List<SalaryComponentRequest> components, Map<String, SalaryComponent> catalogByName) {
        Map<String, Integer> nameOccurrences = new HashMap<>();
        int balancingRemainderCount = 0;

        for (SalaryComponentRequest c : components) {
            String name = c.getComponentName().trim();
            nameOccurrences.merge(name, 1, Integer::sum);

            boolean isBalancingRemainder = SalaryCalculationType.BALANCING_REMAINDER.equals(c.getCalcType());
            boolean isPercentOfComponent = SalaryCalculationType.PERCENT_OF_COMPONENT.equals(c.getCalcType());

            if (isBalancingRemainder) {
                balancingRemainderCount++;
                if (c.getValue() != null) {
                    throw new IllegalArgumentException("Component '" + name + "': value must be omitted for BALANCING_REMAINDER");
                }
            } else if (c.getValue() == null) {
                throw new IllegalArgumentException("Component '" + name + "': value is required unless calcType is BALANCING_REMAINDER");
            }

            if (isPercentOfComponent) {
                if (c.getReferenceComponentName() == null || c.getReferenceComponentName().isBlank()) {
                    throw new IllegalArgumentException("Component '" + name + "': referenceComponentName is required for PERCENT_OF_COMPONENT");
                }
                if (c.getReferenceComponentName().trim().equals(name)) {
                    throw new IllegalArgumentException("Component '" + name + "': cannot reference itself");
                }
            } else if (c.getReferenceComponentName() != null && !c.getReferenceComponentName().isBlank()) {
                throw new IllegalArgumentException("Component '" + name + "': referenceComponentName is only valid for PERCENT_OF_COMPONENT");
            }

            if (c.isUseLowerOfCap() && c.getCap() == null) {
                throw new IllegalArgumentException("Component '" + name + "': useLowerOfCap requires cap to be set");
            }
        }

        nameOccurrences.entrySet().stream().filter(e -> e.getValue() > 1).findFirst().ifPresent(e -> {
            throw new IllegalArgumentException("Duplicate component in structure: '" + e.getKey() + "'");
        });
        if (balancingRemainderCount > 1) {
            throw new IllegalArgumentException("A structure can have at most one BALANCING_REMAINDER component");
        }

        // Second pass, now that every componentName is known to be unique within this request:
        // every PERCENT_OF_COMPONENT reference must point at an actual sibling in this same
        // request (a catalog hit alone isn't enough — it has to be IN this structure).
        for (SalaryComponentRequest c : components) {
            if (SalaryCalculationType.PERCENT_OF_COMPONENT.equals(c.getCalcType())
                    && !nameOccurrences.containsKey(c.getReferenceComponentName().trim())) {
                throw new IllegalArgumentException("Component '" + c.getComponentName().trim() + "': referenceComponentName '"
                        + c.getReferenceComponentName().trim() + "' does not match any component in this structure");
            }
        }
    }

    private SalaryStructureComponent toStructureComponentEntity(SalaryComponentRequest req, SalaryStructure structure, SalaryComponent catalogEntry) {
        return SalaryStructureComponent.builder()
                .structure(structure)
                .component(catalogEntry)
                .calcType(req.getCalcType())
                .value(req.getValue())
                .cap(req.getCap())
                .useLowerOfCap(req.isUseLowerOfCap())
                .build();
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> structureSnapshot(SalaryStructure s, List<SalaryStructureComponent> components) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", s.getName());
        snapshot.put("grade", s.getGrade());
        snapshot.put("financialYear", s.getFinancialYear());
        snapshot.put("componentCount", components.size());
        return snapshot;
    }

    private SalaryStructureResponse toResponse(SalaryStructure s, List<SalaryStructureComponent> components) {
        Map<UUID, String> nameById = components.stream()
                .collect(Collectors.toMap(SalaryStructureComponent::getId, c -> c.getComponent().getName()));
        return SalaryStructureResponse.builder()
                .id(s.getId())
                .name(s.getName())
                .grade(s.getGrade())
                .financialYear(s.getFinancialYear())
                .components(components.stream().map(c -> toComponentResponse(c, nameById)).collect(Collectors.toList()))
                .createdAt(s.getCreatedAt())
                .build();
    }

    private SalaryComponentResponse toComponentResponse(SalaryStructureComponent sc, Map<UUID, String> nameById) {
        SalaryComponent catalogEntry = sc.getComponent();
        return SalaryComponentResponse.builder()
                .id(sc.getId())
                .componentId(catalogEntry.getId())
                .componentName(catalogEntry.getName())
                .type(catalogEntry.getType())
                .taxable(catalogEntry.isTaxable())
                .includedInCtc(catalogEntry.isIncludedInCtc())
                .shownOnPayslip(catalogEntry.isShownOnPayslip())
                .prorateOnLop(catalogEntry.isProrateOnLop())
                .calcType(sc.getCalcType())
                .value(sc.getValue())
                .referenceComponentName(sc.getReferenceStructureComponentId() != null ? nameById.get(sc.getReferenceStructureComponentId()) : null)
                .cap(sc.getCap())
                .useLowerOfCap(sc.isUseLowerOfCap())
                .build();
    }
}

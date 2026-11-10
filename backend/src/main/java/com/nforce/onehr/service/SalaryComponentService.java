package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateSalaryComponentCatalogRequest;
import com.nforce.onehr.dto.payroll.SalaryComponentCatalogResponse;
import com.nforce.onehr.dto.payroll.UpdateSalaryComponentCatalogRequest;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.SalaryComponentRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C20 — the shared Salary Component catalog's own admin CRUD (create/edit/deactivate), same
 * split as {@link LeaveTypeService} from {@link LeaveService}'s read side. {@link
 * SalaryStructureService} is the consumer — it reads this catalog but never writes to it.
 *
 * <p>No delete here, deliberately — US-C20's own rule is "a component used by a structure or a
 * processed payroll cannot be deleted, only deactivated," and since nothing in this codebase
 * distinguishes "never used" from "used," the simplest correct implementation is to never offer
 * delete at all, only {@code active}, toggled through {@link #update}.
 */
@Service
@RequiredArgsConstructor
public class SalaryComponentService {

    private final SalaryComponentRepository salaryComponentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public SalaryComponentCatalogResponse create(CreateSalaryComponentCatalogRequest req, String actorEmail) {
        String name = req.getName().trim();
        if (salaryComponentRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalArgumentException("A salary component named '" + name + "' already exists");
        }

        SalaryComponent component = SalaryComponent.builder()
                .name(name)
                .type(req.getType())
                .taxable(req.isTaxable())
                .taxSection(req.getTaxSection() != null ? req.getTaxSection().trim() : null)
                .includedInCtc(req.isIncludedInCtc())
                .shownOnPayslip(req.isShownOnPayslip())
                .prorateOnLop(req.isProrateOnLop())
                .build();
        component = salaryComponentRepository.save(component);

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(snapshot(component));
        auditService.log(actor.getId(), "SALARY_COMPONENT_CREATED", component.getId(), null, after);

        return toResponse(component);
    }

    @Transactional
    public SalaryComponentCatalogResponse update(UUID id, UpdateSalaryComponentCatalogRequest req, String actorEmail) {
        SalaryComponent component = salaryComponentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Salary component not found: " + id));

        String before = auditSnapshot.toJson(snapshot(component));

        if (req.getName() != null && !req.getName().isBlank()) {
            String newName = req.getName().trim();
            salaryComponentRepository.findByNameIgnoreCase(newName)
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("A salary component named '" + newName + "' already exists");
                    });
            component.setName(newName);
        }
        if (req.getType() != null) component.setType(req.getType());
        if (req.getTaxable() != null) component.setTaxable(req.getTaxable());
        if (req.getTaxSection() != null) component.setTaxSection(req.getTaxSection().trim());
        if (req.getIncludedInCtc() != null) component.setIncludedInCtc(req.getIncludedInCtc());
        if (req.getShownOnPayslip() != null) component.setShownOnPayslip(req.getShownOnPayslip());
        if (req.getProrateOnLop() != null) component.setProrateOnLop(req.getProrateOnLop());
        if (req.getActive() != null) component.setActive(req.getActive());
        component = salaryComponentRepository.save(component);

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(snapshot(component));
        auditService.log(actor.getId(), "SALARY_COMPONENT_UPDATED", component.getId(), before, after);

        return toResponse(component);
    }

    @Transactional(readOnly = true)
    public List<SalaryComponentCatalogResponse> list() {
        return salaryComponentRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> snapshot(SalaryComponent c) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", c.getName());
        snapshot.put("type", c.getType());
        snapshot.put("taxable", c.isTaxable());
        snapshot.put("taxSection", c.getTaxSection());
        snapshot.put("includedInCtc", c.isIncludedInCtc());
        snapshot.put("shownOnPayslip", c.isShownOnPayslip());
        snapshot.put("prorateOnLop", c.isProrateOnLop());
        snapshot.put("active", c.isActive());
        return snapshot;
    }

    private SalaryComponentCatalogResponse toResponse(SalaryComponent c) {
        return SalaryComponentCatalogResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .type(c.getType())
                .taxable(c.isTaxable())
                .taxSection(c.getTaxSection())
                .includedInCtc(c.isIncludedInCtc())
                .shownOnPayslip(c.isShownOnPayslip())
                .prorateOnLop(c.isProrateOnLop())
                .active(c.isActive())
                .build();
    }
}

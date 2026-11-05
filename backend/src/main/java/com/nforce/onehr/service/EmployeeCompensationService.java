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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C02 (ONEHR-579, Phase 1) — assigns a {@link SalaryStructure} and CTC to an employee, and
 * revises it later. Effective-dated the same way as {@link EmployeeManagerHistory}: a revision is
 * never an UPDATE in place, it's INSERT new row + close the prior row's effectiveTo in the same
 * transaction. This service does not resolve the structure's components into actual payslip
 * amounts — that's a Phase 3 (payroll run) concern, not this story's.
 *
 * <p>C03's backdating guard ("reject effectiveFrom falling inside a PayrollRun already
 * APPROVED/DISBURSED") isn't enforced here: {@code PayrollRun} doesn't exist yet (Phase 3). The
 * schema is shaped so that check can be added later without a migration.
 */
@Service
@RequiredArgsConstructor
public class EmployeeCompensationService {

    private final EmployeeCompensationRepository employeeCompensationRepository;
    private final EmployeeRepository employeeRepository;
    private final SalaryStructureRepository salaryStructureRepository;
    private final SalaryStructureComponentRepository salaryStructureComponentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public EmployeeCompensationResponse assign(AssignCompensationRequest req, String actorEmail) {
        if (!employeeRepository.existsById(req.getEmployeeId())) {
            throw new NoSuchElementException("Employee not found: " + req.getEmployeeId());
        }
        SalaryStructure structure = salaryStructureRepository.findById(req.getStructureId())
                .orElseThrow(() -> new NoSuchElementException("Salary structure not found: " + req.getStructureId()));
        if (!salaryStructureComponentRepository.existsByStructureId(structure.getId())) {
            throw new IllegalArgumentException("Salary structure '" + structure.getName() + "' has no components and can't be assigned");
        }

        Optional<EmployeeCompensation> current = employeeCompensationRepository
                .findByEmployeeIdAndEffectiveToIsNull(req.getEmployeeId());
        current.ifPresent(c -> {
            if (!req.getEffectiveFrom().isAfter(c.getEffectiveFrom())) {
                throw new IllegalArgumentException(
                        "effectiveFrom must be after the current compensation's effectiveFrom (" + c.getEffectiveFrom() + ")");
            }
        });

        String before = current.map(c -> auditSnapshot.toJson(compensationSnapshot(c))).orElse(null);

        if (current.isPresent()) {
            employeeCompensationRepository.closeCurrentEntry(req.getEmployeeId(), req.getEffectiveFrom());
        }

        EmployeeCompensation saved = employeeCompensationRepository.save(EmployeeCompensation.builder()
                .employeeId(req.getEmployeeId())
                .structure(structure)
                .ctc(req.getCtc())
                .effectiveFrom(req.getEffectiveFrom())
                .build());

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(compensationSnapshot(saved));
        auditService.log(actor.getId(), "EMPLOYEE_COMPENSATION_ASSIGNED", saved.getId(), before, after);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public EmployeeCompensationResponse getCurrent(UUID employeeId) {
        EmployeeCompensation current = employeeCompensationRepository.findByEmployeeIdAndEffectiveToIsNull(employeeId)
                .orElseThrow(() -> new NoSuchElementException("No current compensation for employee: " + employeeId));
        return toResponse(current);
    }

    @Transactional(readOnly = true)
    public List<EmployeeCompensationResponse> getHistory(UUID employeeId) {
        return employeeCompensationRepository.findByEmployeeIdOrderByEffectiveFromDesc(employeeId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> compensationSnapshot(EmployeeCompensation c) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("structureId", c.getStructure().getId());
        snapshot.put("ctc", c.getCtc());
        snapshot.put("effectiveFrom", c.getEffectiveFrom());
        snapshot.put("effectiveTo", c.getEffectiveTo());
        return snapshot;
    }

    private EmployeeCompensationResponse toResponse(EmployeeCompensation c) {
        return EmployeeCompensationResponse.builder()
                .id(c.getId())
                .employeeId(c.getEmployeeId())
                .structureId(c.getStructure().getId())
                .structureName(c.getStructure().getName())
                .ctc(c.getCtc())
                .effectiveFrom(c.getEffectiveFrom())
                .effectiveTo(c.getEffectiveTo())
                .createdAt(c.getCreatedAt())
                .build();
    }
}

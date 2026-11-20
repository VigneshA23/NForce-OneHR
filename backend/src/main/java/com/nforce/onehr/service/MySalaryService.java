package com.nforce.onehr.service;

import com.nforce.onehr.entity.EmployeeCompensation;
import com.nforce.onehr.entity.PayGroup;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.SalaryStructureComponent;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * US-F07 — the caller's own salary timeline. Identity comes from the login token only. "Current" is the
 * revision in effect today, so a future-dated revision is listed but not tagged until its date.
 */
@Service
@RequiredArgsConstructor
public class MySalaryService {

    public record Component(String name, String type, BigDecimal annual, BigDecimal monthly) {}
    public record Revision(UUID id, String structureName, LocalDate effectiveFrom, LocalDate effectiveTo,
                           BigDecimal ctc, BigDecimal regularSalary, boolean current, List<Component> components) {}
    public record Timeline(BigDecimal currentCtc, String payCycle, String frequency, List<Revision> revisions) {}

    private final EmployeeCompensationRepository compensationRepository;
    private final SalaryStructureComponentRepository structureComponentRepository;
    private final SalaryStructureResolutionService resolutionService;
    private final PayGroupRepository payGroupRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Timeline timeline(String actorEmail) {
        UUID employeeId = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found")).getId();
        LocalDate today = LocalDate.now();
        List<EmployeeCompensation> all = compensationRepository.findByEmployeeIdOrderByEffectiveFromDesc(employeeId);
        List<Revision> revisions = all.stream().map(c -> revision(c, today)).toList();
        Revision current = revisions.stream().filter(Revision::current).findFirst().orElse(null);
        PayGroup pg = all.stream().filter(c -> c.getPayGroupId() != null).findFirst()
                .flatMap(c -> payGroupRepository.findById(c.getPayGroupId())).orElse(null);
        return new Timeline(current == null ? null : current.ctc(),
                pg == null ? null : "Day %d to day %d of the month".formatted(pg.getCycleStartDay(), pg.getCycleEndDay()),
                pg == null ? null : pg.getFrequency(), revisions);
    }

    /** Annual earnings of the revision in effect today (what the income-tax projection pays out), or null. */
    @Transactional(readOnly = true)
    public BigDecimal currentAnnualEarnings(UUID employeeId) {
        LocalDate today = LocalDate.now();
        return compensationRepository.findByEmployeeIdAndEffectiveAt(employeeId, today)
                .map(c -> revision(c, today).regularSalary()).orElse(null);
    }

    private Revision revision(EmployeeCompensation c, LocalDate today) {
        List<SalaryStructureComponent> parts = structureComponentRepository.findByStructureId(c.getStructure().getId());
        Map<UUID, BigDecimal> annual = resolutionService.resolveAnnualValues(parts, c.getCtc());
        List<Component> components = parts.stream().map(sc -> {
            BigDecimal a = annual.get(sc.getId()).setScale(2, RoundingMode.HALF_UP);
            return new Component(sc.getComponent().getName(), sc.getComponent().getType(), a,
                    a.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
        }).toList();
        BigDecimal regular = components.stream().filter(x -> SalaryComponentType.EARNING.equals(x.type()))
                .map(Component::annual).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean current = !c.getEffectiveFrom().isAfter(today) && (c.getEffectiveTo() == null || !c.getEffectiveTo().isBefore(today));
        return new Revision(c.getId(), c.getStructure().getName(), c.getEffectiveFrom(), c.getEffectiveTo(), c.getCtc(), regular, current, components);
    }
}

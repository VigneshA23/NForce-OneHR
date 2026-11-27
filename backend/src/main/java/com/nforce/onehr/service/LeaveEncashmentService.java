package com.nforce.onehr.service;

import com.nforce.onehr.entity.AdHocPayment;
import com.nforce.onehr.entity.EmployeeCompensation;
import com.nforce.onehr.entity.LeaveEncashment;
import com.nforce.onehr.entity.LeavePlanTypeRule;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.SalaryStructureComponent;
import com.nforce.onehr.repository.AdHocPaymentRepository;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.LeaveEncashmentRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.PayrollRunRepository;
import com.nforce.onehr.repository.SalaryComponentRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * LV-05: year-end leave encashment, from amount to payslip.
 * <ul>
 *   <li><b>Amount</b> = days × monthly basis ÷ divisor (26 or 30). The monthly basis is the salary structure's
 *       "Basic" (BASIC) or "Basic" + "DA" / "Dearness Allowance" (BASIC_DA), resolved from the compensation in
 *       effect on the last day of the leave year, the same way payroll resolves it (annual ÷ 12).</li>
 *   <li><b>Payout</b>: a confirmed encashment waits as PENDING until payroll generates (or regenerates) a draft that
 *       includes the employee; it is then added to that run as a "Leave Encashment" ad-hoc payment (ADDED).</li>
 *   <li><b>Rollback</b>: a PENDING encashment is cancelled; an ADDED one too, removing its payment, while its run is
 *       still a draft. Once the run has moved on, the year-end can't be rolled back.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveEncashmentService {

    public static final String COMPONENT = "Leave Encashment";
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final Set<String> DA_NAMES = Set.of("da", "dearness allowance");

    private final EmployeeCompensationRepository compensationRepository;
    private final SalaryStructureComponentRepository structureComponentRepository;
    private final SalaryStructureResolutionService resolutionService;
    private final SalaryComponentRepository salaryComponentRepository;
    private final LeaveEncashmentRepository encashmentRepository;
    private final AdHocPaymentRepository adHocPaymentRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final LeaveTypeRepository leaveTypeRepository;

    /** Monthly Basic (BASIC) or Basic + DA (BASIC_DA) on {@code on}; null when the employee has no salary structure then. */
    @Transactional(readOnly = true)
    public BigDecimal monthlyBase(UUID employeeUserId, LocalDate on, String basis) {
        Optional<EmployeeCompensation> comp = compensationRepository.findByEmployeeIdAndEffectiveAt(employeeUserId, on);
        if (comp.isEmpty() || comp.get().getStructure() == null) return null;
        List<SalaryStructureComponent> components = structureComponentRepository.findByStructureId(comp.get().getStructure().getId());
        Map<UUID, BigDecimal> annual = resolutionService.resolveAnnualValues(components, comp.get().getCtc());
        boolean withDa = LeavePlanTypeRule.ENCASH_BASIC_DA.equals(basis);
        BigDecimal total = BigDecimal.ZERO;
        boolean found = false;
        for (SalaryStructureComponent sc : components) {
            String name = sc.getComponent() == null ? "" : sc.getComponent().getName().trim().toLowerCase();
            if (name.equals("basic") || (withDa && DA_NAMES.contains(name))) {
                total = total.add(annual.getOrDefault(sc.getId(), BigDecimal.ZERO));
                found = true;
            }
        }
        return found ? total.divide(TWELVE, 2, RoundingMode.HALF_UP) : null;
    }

    /** days × monthly basis ÷ divisor, to the paisa. */
    public static BigDecimal amount(BigDecimal days, BigDecimal monthlyBase, int divisor) {
        return days.multiply(monthlyBase).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
    }

    /**
     * Called while payroll generates a draft: adds the PENDING encashments of {@code employeeUserIds} to {@code run}
     * as "Leave Encashment" ad-hoc payments. Nothing (they stay pending) when the component is missing or inactive.
     */
    @Transactional
    public int attachPending(PayrollRun run, Collection<UUID> employeeUserIds, UUID actorId) {
        if (employeeUserIds.isEmpty()) return 0;
        List<LeaveEncashment> pending = encashmentRepository.findByStatusAndEmployeeUserIdIn(LeaveEncashment.PENDING, employeeUserIds);
        if (pending.isEmpty()) return 0;
        Optional<SalaryComponent> component = salaryComponentRepository.findByNameIgnoreCase(COMPONENT)
                .filter(SalaryComponent::isActive).filter(c -> SalaryComponentType.AD_HOC_EARNING.equals(c.getType()));
        if (component.isEmpty()) {
            log.warn("{} leave encashment(s) wait: the '{}' salary component is missing or inactive", pending.size(), COMPONENT);
            return 0;
        }
        Map<UUID, String> typeNames = new HashMap<>();
        for (LeaveType t : leaveTypeRepository.findAllById(pending.stream().map(LeaveEncashment::getLeaveTypeId).distinct().toList())) {
            typeNames.put(t.getId(), t.getName());
        }
        LocalDateTime now = LocalDateTime.now();
        List<LeaveEncashment> added = new ArrayList<>();
        for (LeaveEncashment e : pending) {
            AdHocPayment p = adHocPaymentRepository.save(AdHocPayment.builder()
                    .payrollRun(run)
                    .employeeId(e.getEmployeeUserId())
                    .componentId(component.get().getId())
                    .componentName(component.get().getName())
                    .componentType(component.get().getType())
                    .amount(e.getAmount())
                    .reason("Leave encashment: " + e.getDays().stripTrailingZeros().toPlainString() + " day(s) of "
                            + typeNames.getOrDefault(e.getLeaveTypeId(), "leave") + " (" + e.getFromYear() + ")")
                    .addedBy(e.getCreatedBy() != null ? e.getCreatedBy() : actorId)
                    .addedAt(now)
                    .build());
            e.setStatus(LeaveEncashment.ADDED);
            e.setPayrollRunId(run.getId());
            e.setAdHocPaymentId(p.getId());
            added.add(e);
        }
        encashmentRepository.saveAll(added);
        return added.size();
    }

    /** Why {@code e} can't be cancelled any more (its payroll run has moved past draft), or null. */
    @Transactional(readOnly = true)
    public String blockingReason(LeaveEncashment e) {
        if (!LeaveEncashment.ADDED.equals(e.getStatus()) || e.getPayrollRunId() == null) return null;
        return payrollRunRepository.findById(e.getPayrollRunId())
                .filter(r -> !PayrollRunStatus.DRAFT.equals(r.getStatus()))
                .map(r -> "its encashment is already in a " + r.getStatus().toLowerCase() + " payroll run ("
                        + r.getCycleStart() + " to " + r.getCycleEnd() + ")")
                .orElse(null);
    }

    /** Cancels {@code e}, removing its ad-hoc payment from a draft run. */
    @Transactional
    public void cancel(LeaveEncashment e) {
        if (LeaveEncashment.ADDED.equals(e.getStatus()) && e.getAdHocPaymentId() != null) {
            adHocPaymentRepository.findById(e.getAdHocPaymentId()).ifPresent(adHocPaymentRepository::delete);
        }
        e.setStatus(LeaveEncashment.CANCELLED);
        encashmentRepository.save(e);
    }
}

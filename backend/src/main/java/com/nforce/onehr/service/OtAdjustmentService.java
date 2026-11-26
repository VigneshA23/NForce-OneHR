package com.nforce.onehr.service;

import com.nforce.onehr.dto.overtime.OtDtos.AdjustRequest;
import com.nforce.onehr.dto.overtime.OtDtos.AdjustmentDecisionRequest;
import com.nforce.onehr.dto.overtime.OtDtos.AdjustmentDto;
import com.nforce.onehr.entity.OtAdjustment;
import com.nforce.onehr.entity.OtCalculation;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.OtAdjustmentRepository;
import com.nforce.onehr.repository.OtCalculationRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manual corrections to calculated OT. Every adjustment keeps the original and updated values, a
 * mandatory reason, and who/when. When {@code adjustmentApprovalRequired} is on, a second person
 * holding overtime:CONFIGURE:ALL must approve before the values change.
 */
@Service
@RequiredArgsConstructor
public class OtAdjustmentService {

    private final OtAdjustmentRepository adjustmentRepository;
    private final OtCalculationRepository calculationRepository;
    private final OtCalculationService calculationService;
    private final OtConfigService configService;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public AdjustmentDto request(UUID calculationId, AdjustRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        if (req.otMinutes() == null && req.otAmount() == null) {
            throw new IllegalArgumentException("Provide corrected OT minutes and/or amount");
        }
        if ((req.otMinutes() != null && req.otMinutes() < 0) || (req.otAmount() != null && req.otAmount().signum() < 0)) {
            throw new IllegalArgumentException("Corrected values cannot be negative");
        }
        if (req.reason().isBlank()) throw new IllegalArgumentException("A reason is required for every adjustment");
        OtCalculation calc = calculationRepository.findById(calculationId)
                .orElseThrow(() -> new IllegalArgumentException("OT record not found"));
        if (calc.getPayrollRunId() != null) {
            throw new IllegalStateException("This OT is already part of a payroll run and can no longer be adjusted");
        }
        if (OtCalculation.REJECTED.equals(calc.getStatus())) {
            throw new IllegalStateException("Rejected OT cannot be adjusted");
        }
        if (adjustmentRepository.existsByCalculationIdAndStatus(calculationId, OtAdjustment.PENDING)) {
            throw new IllegalStateException("An adjustment for this OT is already pending approval");
        }

        int newMinutes = req.otMinutes() != null ? req.otMinutes() : calc.getOtMinutes();
        BigDecimal newAmount = req.otAmount() != null ? req.otAmount().setScale(2, RoundingMode.HALF_UP)
                : (newMinutes != calc.getOtMinutes()
                        ? OtCalculationService.amountFor(newMinutes, calc.getHourlyRate(), calc.getMultiplier())
                        : calc.getOtAmount());
        if (newMinutes == calc.getOtMinutes() && newAmount.compareTo(calc.getOtAmount()) == 0) {
            throw new IllegalArgumentException("Corrected values are the same as the current values");
        }

        boolean needsApproval = configService.settings().isAdjustmentApprovalRequired();
        OtAdjustment adj = adjustmentRepository.save(OtAdjustment.builder()
                .calculationId(calc.getId()).originalMinutes(calc.getOtMinutes()).originalAmount(calc.getOtAmount())
                .newMinutes(newMinutes).newAmount(newAmount).reason(req.reason().trim())
                .status(needsApproval ? OtAdjustment.PENDING : OtAdjustment.APPLIED)
                .requestedBy(actor.getId()).build());
        auditService.log(actor.getId(), "OT_ADJUSTMENT_REQUESTED", calc.getId());
        if (!needsApproval) {
            apply(adj, calc, actor.getId(), "Adjustment: " + adj.getReason());
            adj.setDecidedBy(actor.getId());
            adj.setDecidedAt(LocalDateTime.now());
            adjustmentRepository.save(adj);
        }
        return toDtos(List.of(adj)).get(0);
    }

    @Transactional
    public AdjustmentDto decide(UUID adjustmentId, AdjustmentDecisionRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        OtAdjustment adj = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Adjustment not found"));
        if (!OtAdjustment.PENDING.equals(adj.getStatus())) {
            throw new IllegalStateException("Only pending adjustments can be decided");
        }
        if (actor.getId().equals(adj.getRequestedBy())) {
            throw new IllegalStateException("You cannot approve your own adjustment");
        }
        OtCalculation calc = calculationRepository.findById(adj.getCalculationId())
                .orElseThrow(() -> new IllegalStateException("OT record no longer exists"));
        boolean approve = "APPROVE".equals(req.decision());
        if (approve) {
            if (calc.getPayrollRunId() != null) {
                throw new IllegalStateException("This OT is already part of a payroll run");
            }
            apply(adj, calc, actor.getId(), "Adjustment approved: " + adj.getReason());
        } else {
            adj.setStatus(OtAdjustment.REJECTED);
        }
        adj.setDecidedBy(actor.getId());
        adj.setDecidedAt(LocalDateTime.now());
        adj.setDecisionComment(req.comment());
        adjustmentRepository.save(adj);
        auditService.log(actor.getId(), approve ? "OT_ADJUSTMENT_APPROVED" : "OT_ADJUSTMENT_REJECTED", calc.getId());
        notificationService.send(adj.getRequestedBy(), "ATTENDANCE",
                approve ? "OT adjustment approved" : "OT adjustment rejected",
                "Your OT adjustment for " + calc.getWorkDate() + (approve ? " was approved." : " was rejected."),
                "/overtime-management");
        return toDtos(List.of(adj)).get(0);
    }

    @Transactional(readOnly = true)
    public List<AdjustmentDto> list(UUID calculationId) {
        return toDtos(calculationId != null
                ? adjustmentRepository.findByCalculationIdOrderByRequestedAtDesc(calculationId)
                : adjustmentRepository.findAllByOrderByRequestedAtDesc());
    }

    private void apply(OtAdjustment adj, OtCalculation calc, UUID actorId, String note) {
        calc.setOtMinutes(adj.getNewMinutes());
        calc.setOtAmount(adj.getNewAmount());
        calc.setAdjusted(true);
        calculationRepository.save(calc);
        calculationService.record(calc, "ADJUSTED", note + " (was " + adj.getOriginalMinutes() + " min / "
                + adj.getOriginalAmount() + ")", actorId);
        adj.setStatus(OtAdjustment.APPLIED);
    }

    private List<AdjustmentDto> toDtos(List<OtAdjustment> rows) {
        if (rows.isEmpty()) return List.of();
        Map<UUID, OtCalculation> calcs = calculationRepository
                .findAllById(rows.stream().map(OtAdjustment::getCalculationId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(OtCalculation::getId, c -> c));
        Set<UUID> nameIds = new HashSet<>();
        rows.forEach(a -> {
            nameIds.add(a.getRequestedBy());
            if (a.getDecidedBy() != null) nameIds.add(a.getDecidedBy());
            OtCalculation c = calcs.get(a.getCalculationId());
            if (c != null) nameIds.add(c.getEmployeeUserId());
        });
        Map<UUID, String> names = employeeRepository.findNamesByUserIds(nameIds).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1]));
        return rows.stream().map(a -> {
            OtCalculation c = calcs.get(a.getCalculationId());
            return new AdjustmentDto(a.getId(), a.getCalculationId(),
                    c != null ? names.getOrDefault(c.getEmployeeUserId(), "Unknown") : "Unknown",
                    c != null ? c.getWorkDate() : null, a.getOriginalMinutes(), a.getOriginalAmount(),
                    a.getNewMinutes(), a.getNewAmount(), a.getReason(), a.getStatus(),
                    names.get(a.getRequestedBy()), a.getRequestedAt(),
                    a.getDecidedBy() == null ? null : names.get(a.getDecidedBy()), a.getDecidedAt(),
                    a.getDecisionComment());
        }).filter(Objects::nonNull).toList();
    }

    private User actor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}

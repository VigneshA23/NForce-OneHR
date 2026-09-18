package com.nforce.onehr.controller;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.penalization.PenalizationPolicyRequest;
import com.nforce.onehr.dto.penalization.PenalizationPolicyResponse;
import com.nforce.onehr.dto.penalization.PenalizationPolicyVersionSummary;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.PenalizationPolicyResolutionService;
import com.nforce.onehr.service.PenalizationPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Organization Masters → Penalization Policy. SUPER_ADMIN and HR_ADMIN only — Manager and
 * Employee have no route to this <b>configuration</b>, matching {@code DocumentTypeController}'s
 * authorization convention exactly. Every mutation goes through {@link PenalizationPolicyService},
 * which is the only writer of {@code PenalizationPolicyVersion} rows; this controller creates no
 * {@code AttendancePenalty} directly and contains no policy-evaluation logic.
 *
 * <p>Section 25: {@link #myCurrentPolicy} is the one deliberate exception — every authenticated
 * employee may read their own resolved policy (never anyone else's, and never the admin-authoring
 * versions/save endpoints below), overriding the class-level restriction the same way
 * {@code MyRequestsController}'s self-service endpoints are unrestricted-but-self-scoped.
 */
@RestController
@RequestMapping("/api/penalization-policy")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class PenalizationPolicyController {

    private final PenalizationPolicyService service;
    private final PenalizationPolicyResolutionService resolutionService;
    private final EmployeeRepository employeeRepository;
    private final AttendanceProperties attendanceProperties;

    @GetMapping("/current")
    public PenalizationPolicyResponse current(@RequestParam(required = false) UUID policyId) {
        return service.getCurrent(policyId)
                .orElseThrow(() -> new NoSuchElementException("Penalization Policy is not configured"));
    }

    /**
     * The policy actually governing the caller's own attendance right now — resolved through the
     * same {@link PenalizationPolicyResolutionService} the attendance engine and Policy List use,
     * never re-derived. Powers the employee-facing policy explainer (previously hardcoded static
     * text with no backend document behind it).
     */
    @GetMapping("/my-current")
    @PreAuthorize("isAuthenticated()")
    public PenalizationPolicyResponse myCurrentPolicy(Principal principal) {
        Employee employee = employeeRepository.findByUser_Email(principal.getName())
                .orElseThrow(() -> new NoSuchElementException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
        LocalDate today = LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
        UUID policyId = resolutionService.resolveAssignedOrDefaultPolicyId(employee, today);
        return service.getCurrent(policyId)
                .orElseThrow(() -> new NoSuchElementException("Penalization Policy is not configured"));
    }

    @GetMapping("/versions")
    public List<PenalizationPolicyVersionSummary> versions(@RequestParam(required = false) UUID policyId) {
        return service.getVersionHistory(policyId);
    }

    @GetMapping("/versions/{id}")
    public PenalizationPolicyResponse version(@PathVariable UUID id) {
        return service.getVersion(id);
    }

    @PutMapping
    public PenalizationPolicyResponse save(@RequestParam(required = false) UUID policyId,
                                            @Valid @RequestBody PenalizationPolicyRequest request, Principal principal) {
        return service.save(policyId, request, principal.getName());
    }
}

package com.nforce.onehr.service;

import com.nforce.onehr.dto.ProfileChangeFieldDto;
import com.nforce.onehr.dto.ProfileChangeRequestResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ProfileChangeRequest;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ProfileChangeRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.ApprovalGuards;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SensitiveDataMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An employee's own edit of bank / government-ID fields is queued here and only written to
 * {@link Employee} when an {@code employee_sensitive:APPROVE:ALL} holder accepts it. Modelled on
 * OvertimeRequestService, but with no assigned approver: HR is the approver, and never the requester
 * ({@link ApprovalGuards#assertNotOwn}). Lists show identifier-like values masked; the single-request
 * detail shows them raw and is audit-logged like any other unmasked view of sensitive data.
 */
@Service
@RequiredArgsConstructor
public class ProfileChangeRequestService {

    public static final String APPROVE_AUTHORITY = "employee_sensitive:APPROVE:ALL";
    private static final String PENDING = "PENDING";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";

    /** Field key -> label, in display order. These are exactly the fields that wait for approval. */
    static final Map<String, String> FIELD_LABELS = new LinkedHashMap<>();
    static {
        FIELD_LABELS.put("bankAccountNumber", "Bank account number");
        FIELD_LABELS.put("bankName", "Bank name");
        FIELD_LABELS.put("bankIfsc", "IFSC code");
        FIELD_LABELS.put("nationalId", "National ID");
        FIELD_LABELS.put("passportNumber", "Passport number");
        FIELD_LABELS.put("passportExpiry", "Passport expiry");
    }
    /** Identifier-like values: masked as XXXX-1234 everywhere except the audited detail view. */
    private static final Set<String> ID_FIELDS = Set.of("bankAccountNumber", "nationalId", "passportNumber");

    private final ProfileChangeRequestRepository requestRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final NotificationService notificationService;
    private final EffectivePermissionService effectivePermissionService;

    /** Queues the change; a still-pending request for the same employee is replaced, not stacked. */
    @Transactional
    public void submit(UUID employeeId, Map<String, String> oldValues, Map<String, String> newValues) {
        ProfileChangeRequest req = requestRepository.findByEmployeeUserIdAndStatus(employeeId, PENDING)
                .orElseGet(() -> ProfileChangeRequest.builder().employeeUserId(employeeId).build());
        req.setOldValues(new LinkedHashMap<>(oldValues));
        req.setNewValues(new LinkedHashMap<>(newValues));
        req = requestRepository.save(req);

        auditService.log(employeeId, "PROFILE_SENSITIVE_CHANGE_REQUESTED", employeeId, null, fieldsJson(newValues.keySet()));
        String name = employeeName(employeeId);
        for (UUID approverId : effectivePermissionService.activeUserIdsWithAuthority("employee_sensitive", "APPROVE", "ALL")) {
            if (approverId.equals(employeeId)) continue;
            notificationService.send(approverId, "PROFILE_CHANGE_SUBMITTED",
                    "Profile change awaiting approval",
                    name + " changed bank or ID details and is waiting for your approval.",
                    "/approvals?type=PROFILE_CHANGE");
        }
    }

    @Transactional(readOnly = true)
    public boolean hasPending(UUID employeeId) {
        return requestRepository.existsByEmployeeUserIdAndStatus(employeeId, PENDING);
    }

    @Transactional(readOnly = true)
    public List<ProfileChangeRequestResponse> listMine(String actorEmail) {
        User actor = requireActor(actorEmail);
        return requestRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId()).stream()
                .map(r -> toResponse(r, false)).toList();
    }

    /** Everyone's pending requests except the caller's own (they can never decide those). Values masked. */
    @Transactional(readOnly = true)
    public List<ProfileChangeRequestResponse> listPending(String actorEmail) {
        User actor = requireActor(actorEmail);
        requireApprover(actor);
        return requestRepository.findByStatusOrderByCreatedAtAsc(PENDING).stream()
                .filter(r -> !r.getEmployeeUserId().equals(actor.getId()))
                .map(r -> toResponse(r, false)).toList();
    }

    /** Raw values, for the approver to verify against what the employee provided. Audit-logged. */
    @Transactional
    public ProfileChangeRequestResponse detail(UUID requestId, String actorEmail) {
        User actor = requireActor(actorEmail);
        requireApprover(actor);
        ProfileChangeRequest req = requireRequest(requestId);
        ApprovalGuards.assertNotOwn(actor.getId(), req.getEmployeeUserId(), "profile change request");
        auditService.log(actor.getId(), "USER_SENSITIVE_DATA_VIEWED", req.getEmployeeUserId(), null,
                fieldsJson(req.getNewValues().keySet()));
        return toResponse(req, true);
    }

    @Transactional
    public ProfileChangeRequestResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        requireApprover(actor);
        ProfileChangeRequest req = requirePending(requestId);
        ApprovalGuards.assertNotOwn(actor.getId(), req.getEmployeeUserId(), "profile change request");

        Employee emp = employeeRepository.findById(req.getEmployeeUserId())
                .orElseThrow(() -> new IllegalStateException("No employee record for this request"));
        req.getNewValues().forEach((field, value) -> apply(emp, field, value));
        employeeRepository.save(emp);

        decide(req, APPROVED, actor, comment);
        auditService.log(actor.getId(), "PROFILE_SENSITIVE_CHANGE_APPROVED", req.getEmployeeUserId(), null,
                fieldsJson(req.getNewValues().keySet()));
        notificationService.send(req.getEmployeeUserId(), "PROFILE_CHANGE_APPROVED",
                "Profile change approved",
                "Your bank / ID details change has been approved and is now on your profile.",
                "/profile");
        return toResponse(req, false);
    }

    @Transactional
    public ProfileChangeRequestResponse reject(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        requireApprover(actor);
        ProfileChangeRequest req = requirePending(requestId);
        ApprovalGuards.assertNotOwn(actor.getId(), req.getEmployeeUserId(), "profile change request");

        decide(req, REJECTED, actor, comment);
        auditService.log(actor.getId(), "PROFILE_SENSITIVE_CHANGE_REJECTED", req.getEmployeeUserId(), null,
                auditSnapshot.toJson(Map.of("fields", List.copyOf(req.getNewValues().keySet()),
                        "reviewComment", comment != null ? comment : "")));
        notificationService.send(req.getEmployeeUserId(), "PROFILE_CHANGE_REJECTED",
                "Profile change rejected",
                "Your bank / ID details change was rejected"
                        + (comment != null && !comment.isBlank() ? ". Reason: " + comment.trim() : ".")
                        + " Your profile is unchanged.",
                "/profile");
        return toResponse(req, false);
    }

    // ---------------------------------------------------------------- internals

    private void decide(ProfileChangeRequest req, String status, User actor, String comment) {
        req.setStatus(status);
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        requestRepository.save(req);
    }

    private static void apply(Employee emp, String field, String value) {
        switch (field) {
            case "bankAccountNumber" -> emp.setBankAccountNumber(value);
            case "bankName" -> emp.setBankName(value);
            case "bankIfsc" -> emp.setBankIfsc(value);
            case "nationalId" -> emp.setNationalId(value);
            case "passportNumber" -> emp.setPassportNumber(value);
            case "passportExpiry" -> emp.setPassportExpiry(value == null || value.isBlank() ? null : LocalDate.parse(value));
            default -> throw new IllegalStateException("Unknown profile change field: " + field);
        }
    }

    private String fieldsJson(Set<String> fields) {
        return auditSnapshot.toJson(Map.of("fields", List.copyOf(fields)));
    }

    private void requireApprover(User actor) {
        if (!effectivePermissionService.hasAuthority(actor, APPROVE_AUTHORITY))
            throw new AccessDeniedException("Missing " + APPROVE_AUTHORITY);
    }

    private ProfileChangeRequest requireRequest(UUID id) {
        return requestRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Request not found"));
    }

    private ProfileChangeRequest requirePending(UUID id) {
        ProfileChangeRequest req = requireRequest(id);
        if (!PENDING.equals(req.getStatus())) throw new IllegalArgumentException("Only pending requests can be reviewed");
        return req;
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private String employeeName(UUID userId) {
        return employeeRepository.findById(userId).map(Employee::getFullName).orElse("An employee");
    }

    private ProfileChangeRequestResponse toResponse(ProfileChangeRequest r, boolean unmasked) {
        List<ProfileChangeFieldDto> fields = new ArrayList<>();
        FIELD_LABELS.forEach((key, label) -> {
            if (!r.getNewValues().containsKey(key)) return;
            fields.add(new ProfileChangeFieldDto(key, label,
                    show(key, r.getOldValues().get(key), unmasked), show(key, r.getNewValues().get(key), unmasked)));
        });
        return ProfileChangeRequestResponse.builder()
                .id(r.getId()).employeeUserId(r.getEmployeeUserId()).employeeName(employeeName(r.getEmployeeUserId()))
                .status(r.getStatus()).createdAt(r.getCreatedAt()).reviewedAt(r.getReviewedAt())
                .reviewComment(r.getReviewComment()).unmasked(unmasked).fields(fields).build();
    }

    private static String show(String field, String value, boolean unmasked) {
        return !unmasked && ID_FIELDS.contains(field) ? SensitiveDataMasker.maskId(value) : value;
    }
}

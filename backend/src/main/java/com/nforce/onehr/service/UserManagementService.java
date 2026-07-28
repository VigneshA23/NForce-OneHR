package com.nforce.onehr.service;

import com.nforce.onehr.dto.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserManagementService {

    private static final Set<String> PHASE1_ROLES = Set.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final LocationRepository locationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final EmailService emailService;

    /** Super Admin: create a user with any Phase 1 role. */
    @Transactional
    public EmployeeResponse createUser(CreateUserRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);

        String roleCode = req.getRole().toUpperCase();
        if (!PHASE1_ROLES.contains(roleCode))
            throw new IllegalArgumentException("Role '" + roleCode + "' is not a valid Phase 1 role");
        if (userRepository.existsByEmailAndDeletedAtIsNull(req.getEmail().toLowerCase().trim()))
            throw new IllegalArgumentException("A user with this email already exists");

        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleCode));

        String tempPassword = generateTempPassword();
        User newUser = User.builder()
                .email(req.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(tempPassword))
                .mustChangePassword(true)
                .active(true)
                .roles(new HashSet<>(Set.of(role)))
                .build();
        newUser = userRepository.save(newUser);

        String code = resolveCode(req.getEmployeeCode());
        Employee emp = Employee.builder()
                .user(newUser)
                .employeeCode(code)
                .fullName(req.getFullName().trim())
                .employmentType(req.getEmploymentType() != null ? req.getEmploymentType() : "FULL_TIME")
                .workMode(req.getWorkMode() != null ? req.getWorkMode() : "ONSITE")
                .joiningDate(req.getJoiningDate())
                .createdBy(actor.getId())
                .build();

        if (req.getDepartmentId() != null)
            emp.setDepartment(departmentRepository.findById(req.getDepartmentId()).orElse(null));
        if (req.getDesignationId() != null)
            emp.setDesignation(designationRepository.findById(req.getDesignationId()).orElse(null));
        if (req.getLocationId() != null)
            emp.setLocation(locationRepository.findById(req.getLocationId()).orElse(null));

        emp = employeeRepository.save(emp);

        if (req.getManagerId() != null) {
            historyRepository.save(EmployeeManagerHistory.builder()
                    .employeeUserId(newUser.getId())
                    .managerUserId(req.getManagerId())
                    .changedBy(actor.getId())
                    .build());
        }

        auditService.log(actor.getId(), "USER_CREATED", newUser.getId());
        emailService.sendInviteEmail(newUser.getEmail(), req.getFullName().trim(), tempPassword);
        return toResponse(emp, findCurrentManager(newUser.getId()), newUser, tempPassword);
    }

    /** Super Admin: list all users across all roles. */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> listUsers() {
        return employeeRepository.findAllWithDetails().stream()
                .map(e -> toResponse(e, findCurrentManager(e.getUserId()), e.getUser(), null))
                .collect(Collectors.toList());
    }

    /**
     * Super Admin: update all fields.
     * Manager change closes the current history row and inserts a new one — never overwrites.
     */
    @Transactional
    public EmployeeResponse updateUser(UUID userId, UpdateUserRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        Employee emp = employeeRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        User target = emp.getUser();

        if (req.getFullName() != null && !req.getFullName().isBlank())
            emp.setFullName(req.getFullName().trim());
        if (req.getEmploymentType() != null && !req.getEmploymentType().isBlank())
            emp.setEmploymentType(req.getEmploymentType());
        if (req.getWorkMode() != null && !req.getWorkMode().isBlank())
            emp.setWorkMode(req.getWorkMode());
        if (req.getDepartmentId() != null)
            emp.setDepartment(departmentRepository.findById(req.getDepartmentId()).orElse(null));
        if (req.getDesignationId() != null)
            emp.setDesignation(designationRepository.findById(req.getDesignationId()).orElse(null));
        if (req.getLocationId() != null)
            emp.setLocation(locationRepository.findById(req.getLocationId()).orElse(null));

        // Role change
        if (req.getRole() != null && !req.getRole().isBlank()) {
            String roleCode = req.getRole().toUpperCase();
            if (!PHASE1_ROLES.contains(roleCode))
                throw new IllegalArgumentException("Invalid role: " + roleCode);
            Role newRole = roleRepository.findByCode(roleCode)
                    .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleCode));
            target.getRoles().clear();
            target.getRoles().add(newRole);
            userRepository.save(target);
        }

        // Manager change — effective-dating: close current, insert new
        if (req.getManagerId() != null) {
            Optional<EmployeeManagerHistory> current =
                    historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(userId);
            boolean changed = current.map(h -> !h.getManagerUserId().equals(req.getManagerId())).orElse(true);
            if (changed) {
                historyRepository.closeCurrentEntry(userId, LocalDateTime.now());
                historyRepository.save(EmployeeManagerHistory.builder()
                        .employeeUserId(userId)
                        .managerUserId(req.getManagerId())
                        .changedBy(actor.getId())
                        .build());
            }
        }

        emp = employeeRepository.save(emp);
        auditService.log(actor.getId(), "USER_UPDATED", userId);
        return toResponse(emp, findCurrentManager(userId), target, null);
    }

    /** Super Admin: generate new temp password, set must_change_password = true. */
    @Transactional
    public ResetPasswordResponse resetPassword(UUID userId, String actorEmail) {
        User actor = requireActor(actorEmail);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String tempPassword = generateTempPassword();
        target.setPasswordHash(passwordEncoder.encode(tempPassword));
        target.setMustChangePassword(true);
        userRepository.save(target);

        auditService.log(actor.getId(), "PASSWORD_RESET", userId);
        return ResetPasswordResponse.builder()
                .tempPassword(tempPassword)
                .message("Password reset. User must change password on next login.")
                .build();
    }

    /** Super Admin: activate or deactivate. Deactivated user's existing JWT stops working immediately (JWT filter checks isEnabled). */
    @Transactional
    public EmployeeResponse setActiveStatus(UUID userId, boolean active, String actorEmail) {
        User actor = requireActor(actorEmail);
        Employee emp = employeeRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        User target = emp.getUser();
        target.setActive(active);
        userRepository.save(target);
        auditService.log(actor.getId(), active ? "USER_ACTIVATED" : "USER_DEACTIVATED", userId);
        return toResponse(emp, findCurrentManager(userId), target, null);
    }

    /** Super Admin: soft-delete — sets deleted_at. Deleted user's JWT stops working immediately. */
    @Transactional
    public void softDeleteUser(UUID userId, String actorEmail) {
        User actor = requireActor(actorEmail);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (target.getDeletedAt() != null)
            throw new IllegalArgumentException("User is already deleted");
        target.setDeletedAt(Instant.now());
        target.setActive(false);
        userRepository.save(target);
        auditService.log(actor.getId(), "USER_SOFT_DELETED", userId);
    }

    private EmployeeResponse.ManagerRef findCurrentManager(UUID employeeId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .flatMap(h -> userRepository.findById(h.getManagerUserId()))
                .map(mgr -> {
                    String name = employeeRepository.findById(mgr.getId())
                            .map(Employee::getFullName).orElse(mgr.getEmail());
                    return EmployeeResponse.ManagerRef.builder()
                            .userId(mgr.getId().toString())
                            .fullName(name)
                            .email(mgr.getEmail())
                            .build();
                })
                .orElse(null);
    }

    private EmployeeResponse toResponse(Employee emp, EmployeeResponse.ManagerRef manager, User user, String tempPassword) {
        String role = user.getRoles().stream().findFirst().map(Role::getCode).orElse("");
        return EmployeeResponse.builder()
                .userId(emp.getUserId())
                .employeeCode(emp.getEmployeeCode())
                .fullName(emp.getFullName())
                .email(user.getEmail())
                .role(role)
                .departmentId(emp.getDepartment() != null ? emp.getDepartment().getId().toString() : null)
                .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                .designationId(emp.getDesignation() != null ? emp.getDesignation().getId().toString() : null)
                .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                .locationId(emp.getLocation() != null ? emp.getLocation().getId().toString() : null)
                .locationName(emp.getLocation() != null ? emp.getLocation().getName() : null)
                .employmentType(emp.getEmploymentType())
                .workMode(emp.getWorkMode())
                .joiningDate(emp.getJoiningDate())
                .active(user.isActive())
                .currentManager(manager)
                .tempPassword(tempPassword)
                .build();
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private String resolveCode(String requested) {
        if (requested != null && !requested.isBlank()) {
            String code = requested.trim().toUpperCase();
            if (employeeRepository.existsByEmployeeCode(code))
                throw new IllegalArgumentException("Employee code '" + code + "' is already in use");
            return code;
        }
        int next = employeeRepository.findMaxNumericEmployeeCode()
                .map(c -> Integer.parseInt(c.substring(3)) + 1)
                .orElse(1);
        return String.format("NF-%05d", next);
    }

    private String generateTempPassword() {
        int digits = 100000 + RANDOM.nextInt(900000);
        return "OneHR@" + digits;
    }
}

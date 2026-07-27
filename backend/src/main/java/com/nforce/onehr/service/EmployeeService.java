package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateEmployeeRequest;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.UpdateEmployeeRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final LocationRepository locationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * HR Admin + Super Admin. Role is ALWAYS forced to EMPLOYEE regardless of caller.
     */
    @Transactional
    public EmployeeResponse createEmployee(CreateEmployeeRequest req, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));

        if (userRepository.existsByEmail(req.getEmail().toLowerCase().trim())) {
            throw new IllegalArgumentException("A user with this email already exists");
        }

        String tempPassword = generateTempPassword();
        Role employeeRole = roleRepository.findByCode("EMPLOYEE")
                .orElseThrow(() -> new IllegalStateException("EMPLOYEE role not found"));

        User newUser = User.builder()
                .email(req.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(tempPassword))
                .mustChangePassword(true)
                .active(true)
                .roles(new HashSet<>(Set.of(employeeRole)))
                .build();
        newUser = userRepository.save(newUser);

        String code = resolveEmployeeCode(req.getEmployeeCode());

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
            EmployeeManagerHistory history = EmployeeManagerHistory.builder()
                    .employeeUserId(newUser.getId())
                    .managerUserId(req.getManagerId())
                    .changedBy(actor.getId())
                    .build();
            historyRepository.save(history);
        }

        auditService.log(actor.getId(), "EMPLOYEE_CREATED", newUser.getId());
        return toResponse(emp, findCurrentManager(newUser.getId()), newUser, tempPassword);
    }

    /**
     * HR Admin + Super Admin. Returns all employees (role=EMPLOYEE users).
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> listEmployees() {
        List<Employee> emps = employeeRepository.findAllWithDetails();
        return emps.stream()
                .filter(e -> e.getUser().getRoles().stream().anyMatch(r -> r.getCode().equals("EMPLOYEE")))
                .map(e -> toResponse(e, findCurrentManager(e.getUserId()), e.getUser(), null))
                .collect(Collectors.toList());
    }

    /**
     * HR Admin + Super Admin. Updates only dept/designation/location/employmentType/fullName.
     * Manager and role changes are deliberately not handled here — use UserManagementService.
     */
    @Transactional
    public EmployeeResponse updateEmployee(UUID userId, UpdateEmployeeRequest req, String actorEmail) {
        Employee emp = employeeRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));

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

        emp = employeeRepository.save(emp);
        auditService.log(actor.getId(), "EMPLOYEE_UPDATED", userId);
        return toResponse(emp, findCurrentManager(userId), emp.getUser(), null);
    }

    /**
     * Returns users eligible to be assigned as managers (Manager, HR Admin, Super Admin roles).
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> listPotentialManagers() {
        return userRepository.findAll().stream()
                .filter(u -> u.isActive() && u.getRoles().stream()
                        .anyMatch(r -> Set.of("MANAGER", "HR_ADMIN", "SUPER_ADMIN").contains(r.getCode())))
                .map(u -> {
                    String name = employeeRepository.findById(u.getId())
                            .map(Employee::getFullName).orElse(u.getEmail());
                    String role = u.getRoles().stream().findFirst().map(Role::getCode).orElse("");
                    return EmployeeResponse.builder()
                            .userId(u.getId())
                            .email(u.getEmail())
                            .fullName(name)
                            .role(role)
                            .active(u.isActive())
                            .build();
                })
                .collect(Collectors.toList());
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
        String role = user.getRoles().stream().findFirst().map(Role::getCode).orElse("EMPLOYEE");
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

    private String resolveEmployeeCode(String requested) {
        if (requested != null && !requested.isBlank()) {
            String code = requested.trim().toUpperCase();
            if (employeeRepository.existsByEmployeeCode(code))
                throw new IllegalArgumentException("Employee code '" + code + "' is already in use");
            return code;
        }
        int next = employeeRepository.findMaxEmployeeCode()
                .filter(c -> c.startsWith("NF-"))
                .map(c -> {
                    try { return Integer.parseInt(c.substring(3)) + 1; } catch (NumberFormatException e) { return 1; }
                }).orElse(1);
        return String.format("NF-%05d", next);
    }

    private String generateTempPassword() {
        int digits = 100000 + RANDOM.nextInt(900000);
        return "OneHR@" + digits;
    }
}

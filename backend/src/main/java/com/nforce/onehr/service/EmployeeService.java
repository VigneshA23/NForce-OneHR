package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateEmployeeRequest;
import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.ManagerDashboardDto;
import com.nforce.onehr.dto.UpdateEmployeeRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.util.RoleUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
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
    private final AuditSnapshotSerializer auditSnapshot;
    private final EmailService emailService;
    private final LeaveService leaveService;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * HR Admin + Super Admin. Role is ALWAYS forced to EMPLOYEE regardless of caller.
     */
    @Transactional
    public EmployeeResponse createEmployee(CreateEmployeeRequest req, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));

        if (userRepository.existsByEmailAndDeletedAtIsNull(req.getEmail().toLowerCase().trim())) {
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
        leaveService.initializeDefaultBalances(newUser.getId());

        if (req.getManagerId() != null) {
            EmployeeManagerHistory history = EmployeeManagerHistory.builder()
                    .employeeUserId(newUser.getId())
                    .managerUserId(req.getManagerId())
                    .changedBy(actor.getId())
                    .build();
            historyRepository.save(history);
        }

        auditService.log(actor.getId(), "EMPLOYEE_CREATED", newUser.getId());
        emailService.sendInviteEmail(newUser.getEmail(), req.getFullName().trim(), tempPassword);
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
        String before = auditSnapshot.toJson(employeeSnapshot(emp));

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
        String after = auditSnapshot.toJson(employeeSnapshot(emp));
        auditService.log(actor.getId(), "EMPLOYEE_UPDATED", userId, before, after);
        return toResponse(emp, findCurrentManager(userId), emp.getUser(), null);
    }

    private Map<String, Object> employeeSnapshot(Employee emp) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("fullName", emp.getFullName());
        snapshot.put("employmentType", emp.getEmploymentType());
        snapshot.put("workMode", emp.getWorkMode());
        snapshot.put("departmentId", emp.getDepartment() != null ? emp.getDepartment().getId() : null);
        snapshot.put("designationId", emp.getDesignation() != null ? emp.getDesignation().getId() : null);
        snapshot.put("locationId", emp.getLocation() != null ? emp.getLocation().getId() : null);
        return snapshot;
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
                    String role = RoleUtils.primaryRoleCode(u.getRoles(), "");
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

    /**
     * All authenticated users — work-info only, no personal fields.
     */
    @Transactional(readOnly = true)
    public List<DirectoryEntryDto> listDirectory() {
        List<Employee> emps = employeeRepository.findAllWithDetails();
        return emps.stream()
                .map(e -> {
                    var mgr = findCurrentManager(e.getUserId());
                    return DirectoryEntryDto.builder()
                            .userId(e.getUserId().toString())
                            .employeeCode(e.getEmployeeCode())
                            .fullName(e.getFullName())
                            .email(e.getUser().getEmail())
                            .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                            .designationName(e.getDesignation() != null ? e.getDesignation().getTitle() : null)
                            .locationName(e.getLocation() != null ? e.getLocation().getName() : null)
                            .workMode(e.getWorkMode())
                            .employmentType(e.getEmploymentType())
                            .active(e.getUser().isActive())
                            .managerName(mgr != null ? mgr.getFullName() : null)
                            .managerEmail(mgr != null ? mgr.getEmail() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Returns direct reports for the given manager email.
     */
    @Transactional(readOnly = true)
    public ManagerDashboardDto getManagerDashboard(String managerEmail) {
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        List<ManagerDashboardDto.DirectReport> reports = historyRepository
                .findByManagerUserIdAndEffectiveToIsNull(manager.getId())
                .stream()
                .flatMap(rel -> employeeRepository.findById(rel.getEmployeeUserId())
                        .map(emp -> ManagerDashboardDto.DirectReport.builder()
                                .userId(emp.getUserId().toString())
                                .employeeCode(emp.getEmployeeCode())
                                .fullName(emp.getFullName())
                                .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                                .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                                .active(emp.getUser().isActive())
                                .build())
                        .stream())
                .collect(Collectors.toList());

        // Trailing 12 calendar months (including the current one), matching the dashboard
        // chart's own bucketing window. Every history row in that window counts as its own
        // join event, even if that employee has since been reassigned away from this manager —
        // "who joined the team when" should survive a later reassignment/removal.
        LocalDateTime since = LocalDate.now().withDayOfMonth(1).minusMonths(11).atStartOfDay();
        List<ManagerDashboardDto.TeamJoiner> teamJoiners = historyRepository
                .findByManagerUserIdAndEffectiveFromGreaterThanEqual(manager.getId(), since)
                .stream()
                .flatMap(rel -> employeeRepository.findById(rel.getEmployeeUserId())
                        .map(emp -> ManagerDashboardDto.TeamJoiner.builder()
                                .userId(emp.getUserId().toString())
                                .employeeCode(emp.getEmployeeCode())
                                .fullName(emp.getFullName())
                                .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                                .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                                .active(emp.getUser().isActive())
                                .joinedTeamOn(rel.getEffectiveFrom().toLocalDate().toString())
                                .build())
                        .stream())
                .collect(Collectors.toList());

        return ManagerDashboardDto.builder()
                .directReportCount(reports.size())
                .directReports(reports)
                .teamJoiners(teamJoiners)
                .build();
    }

    /**
     * Directory entries for the caller's current peers — colleagues who presently share the
     * caller's manager. Interim "peer group" definition (see
     * {@link com.nforce.onehr.repository.EmployeeManagerHistoryRepository#findCurrentPeerIds});
     * shares the exact same {@link DirectoryEntryDto} shape as {@link #listDirectory()} so the
     * frontend can reuse the same card rendering for both.
     */
    @Transactional(readOnly = true)
    public List<DirectoryEntryDto> listPeers(String employeeEmail) {
        Employee self = employeeRepository.findByUser_Email(employeeEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "No employee profile found for this account. Contact HR to complete your profile."));

        List<UUID> peerIds = historyRepository.findCurrentPeerIds(self.getUserId());
        if (peerIds.isEmpty()) {
            return List.of();
        }
        return employeeRepository.findAllById(peerIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .map(e -> {
                    var mgr = findCurrentManager(e.getUserId());
                    return DirectoryEntryDto.builder()
                            .userId(e.getUserId().toString())
                            .employeeCode(e.getEmployeeCode())
                            .fullName(e.getFullName())
                            .email(e.getUser().getEmail())
                            .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                            .designationName(e.getDesignation() != null ? e.getDesignation().getTitle() : null)
                            .locationName(e.getLocation() != null ? e.getLocation().getName() : null)
                            .workMode(e.getWorkMode())
                            .employmentType(e.getEmploymentType())
                            .active(e.getUser().isActive())
                            .managerName(mgr != null ? mgr.getFullName() : null)
                            .managerEmail(mgr != null ? mgr.getEmail() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /** The caller's own current reporting manager — backs the "Appreciate your lead" card (ONEHR-73). */
    @Transactional(readOnly = true)
    public EmployeeResponse.ManagerRef getMyManager(String employeeEmail) {
        Employee self = employeeRepository.findByUser_Email(employeeEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
        return findCurrentManager(self.getUserId());
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
        String role = RoleUtils.primaryRoleCode(user.getRoles(), "EMPLOYEE");
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

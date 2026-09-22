package com.nforce.onehr.service;

import com.nforce.onehr.dto.ProfileResponse;
import com.nforce.onehr.dto.ProfileTimelineEvent;
import com.nforce.onehr.dto.SetAvatarRequest;
import com.nforce.onehr.dto.UpdateProfileRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.util.RoleUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final long MAX_PHOTO_BYTES = 500 * 1024; // 500 KB
    private static final Set<String> ALLOWED_WORK_MODES = Set.of("REMOTE", "HYBRID", "ONSITE");
    private static final String MASK_GROUP = "•••• ";

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final AttendanceService attendanceService;

    // Not readOnly: isClockedIn() below can trigger a real write (flagging a stale open attendance
    // session as MISSING_CHECKOUT) — the same write AttendanceService#getToday already relies on,
    // and that method is deliberately not readOnly either. Nesting that write inside a readOnly
    // transaction here would risk it being silently suppressed.
    @Transactional
    public ProfileResponse getProfile(String email) {
        User user = requireUser(email);
        return employeeRepository.findById(user.getId())
                .map(emp -> toResponse(user, emp))
                .orElse(toMinimalResponse(user));
    }

    @Transactional(readOnly = true)
    public List<ProfileTimelineEvent> getTimeline(String email) {
        User user = requireUser(email);
        Employee emp = employeeRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("No employee record associated with this account — contact HR"));

        List<ProfileTimelineEvent> events = new ArrayList<>();
        if (emp.getJoiningDate() != null) {
            events.add(ProfileTimelineEvent.builder()
                    .type("JOINED_COMPANY")
                    .date(emp.getJoiningDate())
                    .description("Joined the company")
                    .build());
        }

        List<EmployeeManagerHistory> history =
                historyRepository.findByEmployeeUserIdOrderByEffectiveFromAsc(user.getId());
        // Skip the first row: that's the initial manager assignment at hire, not a "change".
        for (int i = 1; i < history.size(); i++) {
            EmployeeManagerHistory h = history.get(i);
            String managerName = userRepository.findById(h.getManagerUserId())
                    .map(mgr -> employeeRepository.findById(mgr.getId())
                            .map(Employee::getFullName).orElse(mgr.getEmail()))
                    .orElse("Unknown");
            events.add(ProfileTimelineEvent.builder()
                    .type("MANAGER_CHANGED")
                    .date(h.getEffectiveFrom().toLocalDate())
                    .description("Reporting manager changed to " + managerName)
                    .build());
        }

        events.sort(Comparator.comparing(ProfileTimelineEvent::getDate));
        return events;
    }

    @Transactional
    public ProfileResponse updateProfile(String email, UpdateProfileRequest req) {
        User user = requireUser(email);
        Employee emp = employeeRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("No employee record associated with this account — contact HR"));

        if (req.getPhone() != null)                   emp.setPhone(req.getPhone().trim());
        if (req.getDateOfBirth() != null)              emp.setDateOfBirth(req.getDateOfBirth());
        if (req.getGender() != null)                   emp.setGender(req.getGender().trim());
        if (req.getPersonalEmail() != null)            emp.setPersonalEmail(req.getPersonalEmail().trim());
        if (req.getAddress() != null)                  emp.setAddress(req.getAddress().trim());
        if (req.getEmergencyContactName() != null)     emp.setEmergencyContactName(req.getEmergencyContactName().trim());
        if (req.getEmergencyContactPhone() != null)    emp.setEmergencyContactPhone(req.getEmergencyContactPhone().trim());
        if (req.getWorkMode() != null) {
            String wm = req.getWorkMode().toUpperCase().trim();
            if (ALLOWED_WORK_MODES.contains(wm)) emp.setWorkMode(wm);
        }

        boolean nameChanged = false;
        if (req.getFirstName() != null)        { emp.setFirstName(req.getFirstName().trim()); nameChanged = true; }
        if (req.getMiddleName() != null)       { emp.setMiddleName(req.getMiddleName().trim()); nameChanged = true; }
        if (req.getLastName() != null)         { emp.setLastName(req.getLastName().trim()); nameChanged = true; }
        if (req.getPreferredName() != null)    emp.setPreferredName(req.getPreferredName().trim());
        if (req.getBio() != null)              emp.setBio(req.getBio().trim());
        if (req.getMaritalStatus() != null)    emp.setMaritalStatus(req.getMaritalStatus().trim());
        if (req.getEmergencyContactRelationship() != null)
                                                emp.setEmergencyContactRelationship(req.getEmergencyContactRelationship().trim());
        if (req.getPermanentAddress() != null) emp.setPermanentAddress(req.getPermanentAddress().trim());
        if (req.getPassportNumber() != null)   emp.setPassportNumber(req.getPassportNumber().trim());
        if (req.getPassportExpiry() != null)   emp.setPassportExpiry(req.getPassportExpiry());
        if (req.getBankAccountNumber() != null) emp.setBankAccountNumber(req.getBankAccountNumber().trim());
        if (req.getBankName() != null)         emp.setBankName(req.getBankName().trim());
        if (req.getBankIfsc() != null)         emp.setBankIfsc(req.getBankIfsc().trim());
        if (req.getNationalId() != null)       emp.setNationalId(req.getNationalId().trim());

        // fullName is derived, never client-set: join non-blank first/middle/last with single spaces.
        if (nameChanged) {
            emp.setFullName(deriveFullName(emp));
        }

        employeeRepository.save(emp);
        return toResponse(user, emp);
    }

    private String deriveFullName(Employee emp) {
        return Stream.of(emp.getFirstName(), emp.getMiddleName(), emp.getLastName())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
    }

    // Shows only the trailing 4 characters, grouped in 4s (prototype-style "•••• •••• 5591").
    // Returns the input unchanged if null/blank/too short to mask meaningfully.
    private String maskTrailing4(String value) {
        if (value == null || value.isBlank()) return value;
        String trimmed = value.trim();
        if (trimmed.length() <= 4) return trimmed;
        String last4 = trimmed.substring(trimmed.length() - 4);
        int maskedGroups = (trimmed.length() - 4 + 3) / 4;
        return MASK_GROUP.repeat(maskedGroups).trim() + " " + last4;
    }

    @Transactional
    public ProfileResponse uploadPhoto(String email, MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        if (file.getSize() > MAX_PHOTO_BYTES)
            throw new IllegalArgumentException("Photo must be under 500 KB");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/"))
            throw new IllegalArgumentException("File must be an image");

        User user = requireUser(email);
        Employee emp = employeeRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("No employee record associated with this account — contact HR"));
        emp.setProfilePhoto(file.getBytes());
        emp.setAvatarUrl(null); // uploading a real photo replaces any previously chosen avatar
        employeeRepository.save(emp);
        return toResponse(user, emp);
    }

    @Transactional
    public ProfileResponse setAvatar(String email, SetAvatarRequest req) {
        User user = requireUser(email);
        Employee emp = employeeRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("No employee record associated with this account — contact HR"));
        emp.setAvatarUrl(req.getAvatarUrl());
        emp.setProfilePhoto(null); // mutually exclusive with an uploaded photo
        employeeRepository.save(emp);
        return toResponse(user, emp);
    }

    @Transactional
    public ProfileResponse removePhoto(String email) {
        User user = requireUser(email);
        Employee emp = employeeRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("No employee record associated with this account — contact HR"));
        emp.setProfilePhoto(null);
        emp.setAvatarUrl(null);
        employeeRepository.save(emp);
        return toResponse(user, emp);
    }

    private ProfileResponse toMinimalResponse(User user) {
        String role = RoleUtils.primaryRoleCode(user.getRoles(), "EMPLOYEE");
        return ProfileResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getEmail().split("@")[0])
                .role(role)
                .workMode("ONSITE")
                .employmentType("")
                .active(user.isActive())
                .hasEmployeeRecord(false)
                .build();
    }

    private ProfileResponse toResponse(User user, Employee emp) {
        String managerName  = null;
        String managerEmail = null;
        var managerOpt = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(user.getId());
        if (managerOpt.isPresent()) {
            var mgr = userRepository.findById(managerOpt.get().getManagerUserId()).orElse(null);
            if (mgr != null) {
                managerEmail = mgr.getEmail();
                managerName  = employeeRepository.findById(mgr.getId())
                        .map(Employee::getFullName).orElse(mgr.getEmail());
            }
        }

        String role = RoleUtils.primaryRoleCode(user.getRoles(), "EMPLOYEE");

        String photoDataUrl = null;
        if (emp.getProfilePhoto() != null && emp.getProfilePhoto().length > 0) {
            photoDataUrl = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(emp.getProfilePhoto());
        } else if (emp.getAvatarUrl() != null) {
            photoDataUrl = emp.getAvatarUrl();
        }

        return ProfileResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(emp.getFullName())
                .role(role)
                .hasEmployeeRecord(true)
                .photoDataUrl(photoDataUrl)
                .phone(emp.getPhone())
                .dateOfBirth(emp.getDateOfBirth())
                .gender(emp.getGender())
                .personalEmail(emp.getPersonalEmail())
                .address(emp.getAddress())
                .emergencyContactName(emp.getEmergencyContactName())
                .emergencyContactPhone(emp.getEmergencyContactPhone())
                .workMode(emp.getWorkMode())
                .firstName(emp.getFirstName())
                .middleName(emp.getMiddleName())
                .lastName(emp.getLastName())
                .preferredName(emp.getPreferredName())
                .bio(emp.getBio())
                .maritalStatus(emp.getMaritalStatus())
                .emergencyContactRelationship(emp.getEmergencyContactRelationship())
                .permanentAddress(emp.getPermanentAddress())
                .passportNumber(emp.getPassportNumber())
                .passportExpiry(emp.getPassportExpiry())
                .bankAccountNumber(maskTrailing4(emp.getBankAccountNumber()))
                .bankName(emp.getBankName())
                .bankIfsc(emp.getBankIfsc())
                .nationalId(maskTrailing4(emp.getNationalId()))
                .employeeCode(emp.getEmployeeCode())
                .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                .locationName(emp.getLocation() != null ? emp.getLocation().getName() : null)
                .employmentType(emp.getEmploymentType())
                .joiningDate(emp.getJoiningDate())
                .managerName(managerName)
                .managerEmail(managerEmail)
                .active(user.isActive())
                .jobCode(emp.getJobCode())
                .probationEndDate(emp.getProbationEndDate())
                .confirmationDate(emp.getConfirmationDate())
                .businessUnitName(emp.getBusinessUnit() != null ? emp.getBusinessUnit().getName() : null)
                .shiftName(emp.getShift() != null ? emp.getShift().getName() : null)
                .weeklyOffPolicyName(emp.getWeeklyOffPolicy() != null ? emp.getWeeklyOffPolicy().getName() : null)
                .attendancePenalizationPolicyName(
                        emp.getPenalisationPolicy() != null ? emp.getPenalisationPolicy().getName() : null)
                .attendanceStatus(attendanceService.isClockedIn(user.getId()) ? "IN" : "OUT")
                .build();
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}

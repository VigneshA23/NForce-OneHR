package com.nforce.onehr.service;

import com.nforce.onehr.dto.ProfileResponse;
import com.nforce.onehr.dto.UpdateProfileRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final long MAX_PHOTO_BYTES = 500 * 1024; // 500 KB
    private static final Set<String> ALLOWED_WORK_MODES = Set.of("REMOTE", "HYBRID", "ONSITE");

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(String email) {
        User user = requireUser(email);
        Employee emp = requireEmployee(user.getId().toString());
        return toResponse(user, emp);
    }

    @Transactional
    public ProfileResponse updateProfile(String email, UpdateProfileRequest req) {
        User user = requireUser(email);
        Employee emp = requireEmployee(user.getId().toString());

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

        employeeRepository.save(emp);
        return toResponse(user, emp);
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
        Employee emp = requireEmployee(user.getId().toString());
        emp.setProfilePhoto(file.getBytes());
        employeeRepository.save(emp);
        return toResponse(user, emp);
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

        String role = user.getRoles().stream().findFirst().map(Role::getCode).orElse("EMPLOYEE");

        String photoDataUrl = null;
        if (emp.getProfilePhoto() != null && emp.getProfilePhoto().length > 0) {
            photoDataUrl = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(emp.getProfilePhoto());
        }

        return ProfileResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(emp.getFullName())
                .role(role)
                .photoDataUrl(photoDataUrl)
                .phone(emp.getPhone())
                .dateOfBirth(emp.getDateOfBirth())
                .gender(emp.getGender())
                .personalEmail(emp.getPersonalEmail())
                .address(emp.getAddress())
                .emergencyContactName(emp.getEmergencyContactName())
                .emergencyContactPhone(emp.getEmergencyContactPhone())
                .workMode(emp.getWorkMode())
                .employeeCode(emp.getEmployeeCode())
                .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                .locationName(emp.getLocation() != null ? emp.getLocation().getName() : null)
                .employmentType(emp.getEmploymentType())
                .joiningDate(emp.getJoiningDate())
                .managerName(managerName)
                .managerEmail(managerEmail)
                .active(user.isActive())
                .build();
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    private Employee requireEmployee(String userId) {
        return employeeRepository.findById(java.util.UUID.fromString(userId))
                .orElseThrow(() -> new IllegalStateException("Employee record not found"));
    }
}

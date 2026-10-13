package com.nforce.onehr.controller;

import com.nforce.onehr.dto.*;
import com.nforce.onehr.service.UserManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserManagementController {

    private final UserManagementService userManagementService;

    @GetMapping
    @PreAuthorize("hasAuthority('user_management:VIEW:ALL')")
    public List<EmployeeResponse> listUsers() {
        return userManagementService.listUsers();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('user_management:CREATE:ALL')")
    public EmployeeResponse createUser(@Valid @RequestBody CreateUserRequest req, Principal principal) {
        return userManagementService.createUser(req, principal.getName());
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasAuthority('user_management:EDIT:ALL')")
    public EmployeeResponse updateUser(@PathVariable UUID userId,
                                       @Valid @RequestBody UpdateUserRequest req,
                                       Principal principal) {
        return userManagementService.updateUser(userId, req, principal.getName());
    }

    /** Joining date is deliberately not part of updateUser — see UserManagementService for why. */
    @PatchMapping("/{userId}/joining-date")
    @PreAuthorize("hasAuthority('user_management:EDIT:ALL')")
    public EmployeeResponse updateJoiningDate(@PathVariable UUID userId,
                                              @Valid @RequestBody UpdateJoiningDateRequest req,
                                              Principal principal) {
        return userManagementService.updateJoiningDate(userId, req, principal.getName());
    }

    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("hasAuthority('user_management:EDIT:ALL')")
    public ResetPasswordResponse resetPassword(@PathVariable UUID userId, Principal principal) {
        return userManagementService.resetPassword(userId, principal.getName());
    }

    @PatchMapping("/{userId}/status")
    @PreAuthorize("hasAuthority('user_management:EDIT:ALL')")
    public EmployeeResponse setStatus(@PathVariable UUID userId,
                                      @RequestBody Map<String, Boolean> body,
                                      Principal principal) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return userManagementService.setActiveStatus(userId, active, principal.getName());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('user_management:DELETE:ALL')")
    public void softDeleteUser(@PathVariable UUID userId, Principal principal) {
        userManagementService.softDeleteUser(userId, principal.getName());
    }

    /** Additive secondary role (e.g. Payroll Admin) — distinct from the primary-role swap above. */
    @PostMapping("/{userId}/additional-roles/{roleCode}")
    @PreAuthorize("hasAuthority('user_management:EDIT:ALL')")
    public EmployeeResponse grantAdditionalRole(@PathVariable UUID userId, @PathVariable String roleCode, Principal principal) {
        return userManagementService.grantAdditionalRole(userId, roleCode, principal.getName());
    }

    @DeleteMapping("/{userId}/additional-roles/{roleCode}")
    @PreAuthorize("hasAuthority('user_management:EDIT:ALL')")
    public EmployeeResponse revokeAdditionalRole(@PathVariable UUID userId, @PathVariable String roleCode, Principal principal) {
        return userManagementService.revokeAdditionalRole(userId, roleCode, principal.getName());
    }
}

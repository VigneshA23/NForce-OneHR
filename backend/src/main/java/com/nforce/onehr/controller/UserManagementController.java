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
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UserManagementController {

    private final UserManagementService userManagementService;

    @GetMapping
    public List<EmployeeResponse> listUsers() {
        return userManagementService.listUsers();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse createUser(@Valid @RequestBody CreateUserRequest req, Principal principal) {
        return userManagementService.createUser(req, principal.getName());
    }

    @PatchMapping("/{userId}")
    public EmployeeResponse updateUser(@PathVariable UUID userId,
                                       @Valid @RequestBody UpdateUserRequest req,
                                       Principal principal) {
        return userManagementService.updateUser(userId, req, principal.getName());
    }

    /** Joining date is deliberately not part of updateUser — see UserManagementService for why. */
    @PatchMapping("/{userId}/joining-date")
    public EmployeeResponse updateJoiningDate(@PathVariable UUID userId,
                                              @Valid @RequestBody UpdateJoiningDateRequest req,
                                              Principal principal) {
        return userManagementService.updateJoiningDate(userId, req, principal.getName());
    }

    @PostMapping("/{userId}/reset-password")
    public ResetPasswordResponse resetPassword(@PathVariable UUID userId, Principal principal) {
        return userManagementService.resetPassword(userId, principal.getName());
    }

    @PatchMapping("/{userId}/status")
    public EmployeeResponse setStatus(@PathVariable UUID userId,
                                      @RequestBody Map<String, Boolean> body,
                                      Principal principal) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return userManagementService.setActiveStatus(userId, active, principal.getName());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDeleteUser(@PathVariable UUID userId, Principal principal) {
        userManagementService.softDeleteUser(userId, principal.getName());
    }
}

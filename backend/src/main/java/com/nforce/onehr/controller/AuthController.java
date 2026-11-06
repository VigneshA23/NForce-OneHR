package com.nforce.onehr.controller;

import com.nforce.onehr.dto.*;
import com.nforce.onehr.security.JwtTokenProvider;
import com.nforce.onehr.service.AuthService;
import com.nforce.onehr.service.OfficeNetwork;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final OfficeNetwork officeNetwork;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest httpRequest) {
        LoginResponse response = authService.login(
                request,
                officeNetwork.clientIp(httpRequest),
                httpRequest.getHeader("User-Agent")
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/change-password")
    public ResponseEntity<ChangePasswordResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest,
            Authentication authentication) {
        // The filter already validated this bearer token, so it's safe to read its claim here.
        String header = httpRequest.getHeader("Authorization");
        boolean rememberMe = header != null && header.startsWith("Bearer ")
                && jwtTokenProvider.extractRememberMe(header.substring(7));
        ChangePasswordResponse response = authService.changePassword(
                request,
                authentication.getName(),
                rememberMe
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                                                  HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.forgotPassword(request.getEmail(), httpRequest.getHeader("Origin")));
    }
}

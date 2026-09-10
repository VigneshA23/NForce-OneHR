package com.nforce.onehr.controller;

import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.ForceLogoutBroadcaster;
import com.nforce.onehr.security.SseTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * Push channel for server-initiated logout (currently: a Super Admin profile change — see
 * UserManagementService#updateUser). Kept separate from AuthController/AuthService since this is
 * a connection-lifecycle concern, not a login/credentials one.
 *
 * GET /api/auth/events is permitAll at the security-filter level (see SecurityConfig) because a
 * native browser EventSource can't send a Bearer header — it's ticket-authenticated instead, via
 * the one-time ticket minted by the (normally-authenticated) POST below.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SessionEventController {

    private final SseTicketService sseTicketService;
    private final ForceLogoutBroadcaster forceLogoutBroadcaster;
    private final UserRepository userRepository;

    @PostMapping("/events/ticket")
    public Map<String, String> issueTicket(Authentication authentication) {
        UUID userId = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Actor not found"))
                .getId();
        return Map.of("ticket", sseTicketService.issue(userId));
    }

    @GetMapping("/events")
    public SseEmitter events(@RequestParam String ticket) {
        UUID userId = sseTicketService.redeem(ticket);
        if (userId == null) {
            throw new AccessDeniedException("Invalid or expired ticket");
        }
        return forceLogoutBroadcaster.register(userId);
    }
}

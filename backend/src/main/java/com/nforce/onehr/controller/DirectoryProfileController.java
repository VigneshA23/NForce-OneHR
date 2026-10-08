package com.nforce.onehr.controller;

import com.nforce.onehr.dto.DirectoryProfileResponse;
import com.nforce.onehr.service.DirectoryProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/directory")
@RequiredArgsConstructor
public class DirectoryProfileController {

    private final DirectoryProfileService service;

    @GetMapping("/{userId}/profile")
    public DirectoryProfileResponse getDirectoryProfile(@PathVariable UUID userId, Authentication auth) {
        return service.getDirectoryProfile(auth.getName(), userId);
    }
}

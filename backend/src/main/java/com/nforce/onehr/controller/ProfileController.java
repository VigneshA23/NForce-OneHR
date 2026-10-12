package com.nforce.onehr.controller;

import com.nforce.onehr.dto.ProfileResponse;
import com.nforce.onehr.dto.ProfileTimelineEvent;
import com.nforce.onehr.dto.SetAvatarRequest;
import com.nforce.onehr.dto.UpdateProfileRequest;
import com.nforce.onehr.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile(Authentication auth) {
        return ResponseEntity.ok(profileService.getProfile(auth.getName()));
    }

    // "View Profile" from the People Directory — any other employee's About/Job detail, with
    // sensitive self-service fields redacted unless the caller is HR_ADMIN/SUPER_ADMIN. See
    // ProfileService#getProfileForViewer.
    @GetMapping("/{userId}")
    public ResponseEntity<ProfileResponse> getProfileForViewer(@PathVariable UUID userId, Authentication auth) {
        return ResponseEntity.ok(profileService.getProfileForViewer(auth.getName(), userId));
    }

    @GetMapping("/timeline")
    public ResponseEntity<List<ProfileTimelineEvent>> getTimeline(Authentication auth) {
        return ResponseEntity.ok(profileService.getTimeline(auth.getName()));
    }

    @PatchMapping
    public ResponseEntity<ProfileResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest req,
            Authentication auth) {
        return ResponseEntity.ok(profileService.updateProfile(auth.getName(), req));
    }

    @PostMapping("/photo")
    public ResponseEntity<ProfileResponse> uploadPhoto(
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {
        return ResponseEntity.ok(profileService.uploadPhoto(auth.getName(), file));
    }

    @DeleteMapping("/photo")
    public ResponseEntity<ProfileResponse> removePhoto(Authentication auth) {
        return ResponseEntity.ok(profileService.removePhoto(auth.getName()));
    }

    @PutMapping("/avatar")
    public ResponseEntity<ProfileResponse> setAvatar(
            @Valid @RequestBody SetAvatarRequest req,
            Authentication auth) {
        return ResponseEntity.ok(profileService.setAvatar(auth.getName(), req));
    }

    @PostMapping("/cover")
    public ResponseEntity<ProfileResponse> uploadCover(
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {
        return ResponseEntity.ok(profileService.uploadCover(auth.getName(), file));
    }

    @DeleteMapping("/cover")
    public ResponseEntity<ProfileResponse> removeCover(Authentication auth) {
        return ResponseEntity.ok(profileService.removeCover(auth.getName()));
    }
}

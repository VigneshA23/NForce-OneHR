package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SetAvatarRequest {
    @NotBlank
    // Restricted to DiceBear's own host — this becomes a stored URL the photo-serving endpoint
    // redirects any authenticated user to (see EmployeeController#photo), so it must not be an
    // open redirect to an arbitrary attacker-supplied URL.
    @Pattern(regexp = "^https://api\\.dicebear\\.com/.*", message = "avatarUrl must be a dicebear.com URL")
    private String avatarUrl;
}

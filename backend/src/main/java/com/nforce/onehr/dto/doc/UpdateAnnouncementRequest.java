package com.nforce.onehr.dto.doc;

import lombok.Data;

@Data
public class UpdateAnnouncementRequest {
    private String title;
    private String body;
    private String audience;
}

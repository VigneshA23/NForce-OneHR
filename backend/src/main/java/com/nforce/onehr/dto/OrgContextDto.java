package com.nforce.onehr.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrgContextDto {

    private PersonCard focusUser;
    private PersonCard manager;          // null if focus has no manager (root node)
    private List<PersonCard> peers;      // includes focusUser (isFocus=true)
    private List<PersonCard> directReports;
    private List<BreadcrumbEntry> breadcrumb;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PersonCard {
        private String id;
        private String name;
        private String designation;
        private String department;
        private String initials;
        private int directReportsCount;
        @JsonProperty("isFocus")
        private boolean isFocus;
        private boolean active;
        private String primaryRole;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BreadcrumbEntry {
        private String id;
        private String name;
    }
}

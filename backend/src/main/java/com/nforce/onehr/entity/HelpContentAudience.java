package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One audience a {@link HelpContent} row is published to — multiple rows per content, chosen by
 * the publisher in the Review &amp; Publish flow (see {@code HelpContentService#publish}), not
 * on the Add/Edit form. Value is one of EMPLOYEE | MANAGER | HR | ADMIN — the same 4-bucket
 * collapse the frontend's {@code toShellRole()}/{@code nav.config.ts} already uses for the real
 * Role codes (see {@code RoleUtils#audienceBuckets}). No rows for a content id means "visible to
 * everyone" — the same as this feature's pre-existing unfiltered behavior.
 */
@Entity
@Table(name = "help_content_audience")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HelpContentAudience {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(nullable = false, length = 20)
    private String audience;
}

package com.nforce.onehr.ai.contract;

import java.util.Arrays;
import java.util.Optional;

/**
 * The kind of knowledge unit a document/chunk represents. Persisted as a plain string in
 * {@code ai_knowledge_chunk.knowledge_type} — mirroring how this codebase already stores
 * {@code help_content.type} and {@code help_content.status} as VARCHAR rather than a JPA enum.
 */
public enum KnowledgeType {
    /** What OneHR is, global rules, navigation concepts. */
    FOUNDATION,
    /** Role definitions and what each role can do. */
    ROLE,
    /** A module overview. */
    MODULE,
    /** A page: purpose, who can reach it, what's on it. */
    PAGE,
    /** A single user action with steps, inputs, validations and outcomes. The most important unit. */
    ACTION,
    /** A multi-step / multi-actor process. */
    WORKFLOW,
    /** A known error, its meaning, causes and resolution. */
    ERROR,
    /** An FAQ or how-to note, including content ingested from Help &amp; Guidance. */
    FAQ,
    /** Canonical term plus synonyms and informal phrasings. */
    TERM;

    public static Optional<KnowledgeType> fromCode(String code) {
        if (code == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(code.trim()))
                .findFirst();
    }
}

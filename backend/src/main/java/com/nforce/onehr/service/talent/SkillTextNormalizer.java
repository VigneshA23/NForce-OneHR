package com.nforce.onehr.service.talent;

/**
 * The one normalization rule shared by skill_alias.normalized_alias (SkillMasterService) and
 * skill_migration_candidate.normalized_text (the one-time free-text migration scan) — both need
 * the exact same "same skill, different spelling" equivalence, so this is the single
 * implementation either call site may use, per the Talent & Skills spec's "trim, case-insensitive,
 * strip punctuation" rule.
 */
public final class SkillTextNormalizer {

    private SkillTextNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        // +/#/. are kept, not stripped as "punctuation" — "C++", "C#", ".NET", and "Node.js" would
        // otherwise all collapse into unrelated, misleading normalized forms ("c", "c", "net",
        // "nodejs") that no longer distinguish them from each other or from "C"/"Net"/"Nodejs".
        return raw.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9+#. ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}

package com.nforce.onehr.ai.eval;

import lombok.Data;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code ai-eval/questions.yaml}.
 *
 * <p>Shared by the two things that read it: the fixture validator, which runs in the normal suite
 * and costs nothing, and the live harness, which costs a real API call per question. Parsing it
 * twice in two slightly different ways is exactly how the two would drift apart.
 *
 * <p>Parsing is strict. A question that fails to parse is a question that silently stops being
 * asked, and an evaluation set with holes in it is worse than none — it reports a pass rate for a
 * subset nobody chose.
 */
public final class EvaluationSet {

    private static final String RESOURCE = "/ai-eval/questions.yaml";

    /** Sentinel in {@code expect.navigation} meaning "no page may be offered". */
    public static final String NO_NAVIGATION = "none";

    private EvaluationSet() {}

    public static List<EvalQuestion> load() {
        Map<String, Object> root;
        try (InputStream in = EvaluationSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing " + RESOURCE);
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
        if (!(root.get("questions") instanceof List<?> entries)) {
            throw new IllegalStateException(RESOURCE + " must contain a top-level 'questions' list");
        }

        List<EvalQuestion> questions = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Each entry under 'questions' must be a mapping");
            }
            questions.add(toQuestion(map));
        }
        return questions;
    }

    private static EvalQuestion toQuestion(Map<?, ?> map) {
        Object rawExpect = map.get("expect");
        Map<?, ?> expect = rawExpect instanceof Map<?, ?> m ? m : Map.of();

        EvalQuestion question = new EvalQuestion();
        question.setId(string(map.get("id"), "id"));
        question.setRole(string(map.get("role"), "role for " + map.get("id")));
        question.setQuestion(string(map.get("question"), "question for " + map.get("id")));
        question.setCategory(string(map.get("category"), "category for " + map.get("id")));
        question.setTypes(stringSet(expect.get("type")));
        question.setNavigation(optional(expect.get("navigation")));
        question.setKnowledge(stringList(expect.get("knowledge")));
        question.setMustMention(stringList(expect.get("mustMention")));
        question.setMustMentionAny(stringList(expect.get("mustMentionAny")));
        question.setMustNotMention(stringList(expect.get("mustNotMention")));
        question.setWhy(optional(map.get("why")));
        return question;
    }

    private static String string(Object value, String what) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("Missing or blank " + what + " in " + RESOURCE);
        }
        return s.trim();
    }

    private static String optional(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) values.add(String.valueOf(item).trim());
        }
        return List.copyOf(values);
    }

    private static Set<String> stringSet(Object value) {
        return new LinkedHashSet<>(stringList(value));
    }

    /** One question and what a correct answer to it looks like. */
    @Data
    public static class EvalQuestion {
        private String id;
        private String role;
        private String question;
        private String category;
        /** Acceptable response types; any one of them passes. */
        private Set<String> types;
        /** A pageId that must be offered, {@link #NO_NAVIGATION}, or null for "don't care". */
        private String navigation;
        /** At least one of these must have been retrieved. Checked by the live harness only. */
        private List<String> knowledge;
        private List<String> mustMention;
        /**
         * At least one of these must appear. Needed because models spell small numbers out: an
         * answer saying "a maximum of three per month" is correct, and asserting the digit alone
         * fails it for being well written.
         */
        private List<String> mustMentionAny;
        private List<String> mustNotMention;
        /** Present only where the question guards a specific way of being wrong. */
        private String why;

        public boolean forbidsNavigation() {
            return NO_NAVIGATION.equalsIgnoreCase(navigation);
        }

        public boolean requiresNavigation() {
            return navigation != null && !forbidsNavigation();
        }
    }
}

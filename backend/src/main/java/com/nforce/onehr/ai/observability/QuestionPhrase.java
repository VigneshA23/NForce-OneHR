package com.nforce.onehr.ai.observability;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a question NORA could not answer into a phrase that is safe to keep and easy to group.
 *
 * <p>Lower-cased, whitespace collapsed, trailing punctuation dropped, so "How do I apply for
 * leave?" and "how do i apply for leave" count as one. Emails, URLs and anything containing a digit
 * (phone numbers, employee ids, amounts, dates) are masked.
 *
 * <p>ponytail: names are not detected — there is no reliable way without NLP. Access to the
 * phrases is Super Admin only; add a name dictionary or NER if that is not enough.
 */
public final class QuestionPhrase {

    static final int MAX_CHARS = 120;

    private static final Pattern EMAIL = Pattern.compile("\\S+@\\S+");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\S*\\d\\S*");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[\\s?!.,;:]+$");

    private QuestionPhrase() {}

    /** @return the sanitised phrase, or null when nothing is left to store */
    public static String sanitize(String question) {
        if (question == null) return null;
        String s = URL.matcher(question).replaceAll("[link]");
        s = EMAIL.matcher(s).replaceAll("[email]");
        s = HAS_DIGIT.matcher(s).replaceAll("[number]");
        s = SPACES.matcher(s.toLowerCase(Locale.ROOT).trim()).replaceAll(" ");
        s = TRAILING_PUNCT.matcher(s).replaceAll("");
        if (s.length() > MAX_CHARS) s = s.substring(0, MAX_CHARS).trim();
        return s.isEmpty() ? null : s;
    }
}

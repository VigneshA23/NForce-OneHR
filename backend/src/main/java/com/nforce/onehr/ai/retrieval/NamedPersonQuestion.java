package com.nforce.onehr.ai.retrieval;

import java.util.regex.Pattern;

/**
 * Deterministically detects a question about a specific named person - "tell me about X", "who is
 * X" - regardless of how X is capitalised, spelled, or whether it resembles anything retrieval has
 * seen before.
 *
 * <p>Semantic retrieval alone is not reliable enough for this one shape of question (ONEHR - AI
 * chatbot fails to handle duplicate employee names). Measured against the real embedding model,
 * "tell me about aanuj" scored 0.5955 against the knowledge unit that answers it while "tell me
 * about Aanuj" - the same question, differing only in one capital letter - scored 0.6683: the
 * question straddles the retrieval score floor on capitalisation alone. Worse, a name that
 * resembles none of the unit's own synonyms can score low regardless of case ("who is rakesh"
 * measured 0.565) - no synonym list can cover every real name in the organisation, so tuning
 * wording only ever fixes the specific names tried.
 *
 * <p>So the <em>shape</em> of the question is detected here, deterministically, the same way
 * {@link com.nforce.onehr.ai.data.MyTeamDateRange} detects a date expression instead of trusting
 * the model to read "last week" correctly. This decides only whether the question is of this kind
 * at all - never which person is meant, which stays entirely downstream, decided by the model from
 * data it was already authorised to receive.
 */
public final class NamedPersonQuestion {

    private NamedPersonQuestion() {}

    private static final Pattern PATTERN = Pattern.compile(
            "\\b(tell me about|who is|who's|whos|(details|info|information|profile)\\s+(for|of|on|about))\\b",
            Pattern.CASE_INSENSITIVE);

    public static boolean asks(String question) {
        return question != null && PATTERN.matcher(question).find();
    }
}

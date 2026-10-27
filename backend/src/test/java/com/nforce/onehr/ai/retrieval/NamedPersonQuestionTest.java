package com.nforce.onehr.ai.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamedPersonQuestionTest {

    @Test
    void recognisesTellMeAboutRegardlessOfCase() {
        assertThat(NamedPersonQuestion.asks("tell me about aanuj")).isTrue();
        assertThat(NamedPersonQuestion.asks("Tell me about Aanuj")).isTrue();
        assertThat(NamedPersonQuestion.asks("TELL ME ABOUT AANUJ")).isTrue();
    }

    @Test
    void recognisesWhoIsInItsCommonSpellings() {
        assertThat(NamedPersonQuestion.asks("who is rakesh")).isTrue();
        assertThat(NamedPersonQuestion.asks("Who's Praveen Gurram?")).isTrue();
        assertThat(NamedPersonQuestion.asks("whos anil")).isTrue();
    }

    @Test
    void recognisesDetailsForX() {
        assertThat(NamedPersonQuestion.asks("Show details for Praveen G.")).isTrue();
        assertThat(NamedPersonQuestion.asks("give me info on Rakesh")).isTrue();
    }

    @Test
    void ignoresUnrelatedQuestions() {
        assertThat(NamedPersonQuestion.asks("how do I apply for leave")).isFalse();
        assertThat(NamedPersonQuestion.asks("what is my leave balance")).isFalse();
        assertThat(NamedPersonQuestion.asks("")).isFalse();
    }

    @Test
    void toleratesNull() {
        assertThat(NamedPersonQuestion.asks(null)).isFalse();
    }
}

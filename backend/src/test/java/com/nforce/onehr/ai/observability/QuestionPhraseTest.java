package com.nforce.onehr.ai.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionPhraseTest {

    @Test
    @DisplayName("case, spacing and trailing punctuation do not split a phrase")
    void groupsEquivalentQuestions() {
        assertThat(QuestionPhrase.sanitize("How do I apply for leave?"))
                .isEqualTo(QuestionPhrase.sanitize("  how do i   APPLY for leave ?? "))
                .isEqualTo("how do i apply for leave");
    }

    @Test
    @DisplayName("emails, links and anything with a digit are masked")
    void masksSensitiveTokens() {
        assertThat(QuestionPhrase.sanitize("Send payslip to jane.doe@nforceone.com"))
                .isEqualTo("send payslip to [email]");
        assertThat(QuestionPhrase.sanitize("Why is EMP1234 salary 85,000 and call 9876543210"))
                .isEqualTo("why is [number] salary [number] and call [number]");
        assertThat(QuestionPhrase.sanitize("see https://intranet.local/x?id=7 please"))
                .isEqualTo("see [link] please");
        assertThat(QuestionPhrase.sanitize("update bank 1234-5678-9012")).doesNotContain("1234");
    }

    @Test
    @DisplayName("blank input stores nothing and long input is capped")
    void edges() {
        assertThat(QuestionPhrase.sanitize("  ?? ")).isNull();
        assertThat(QuestionPhrase.sanitize(null)).isNull();
        assertThat(QuestionPhrase.sanitize("a ".repeat(200))).hasSizeLessThanOrEqualTo(QuestionPhrase.MAX_CHARS);
    }
}

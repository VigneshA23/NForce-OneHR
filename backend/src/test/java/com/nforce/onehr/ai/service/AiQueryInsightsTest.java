package com.nforce.onehr.ai.service;

import com.nforce.onehr.ai.controller.AiAssistantController;
import com.nforce.onehr.ai.dto.AiQueryInsightsResponse;
import com.nforce.onehr.ai.entity.AiInteractionLog;
import com.nforce.onehr.ai.repository.AiInteractionLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiQueryInsightsTest {

    private final AiInteractionLogRepository repository = mock(AiInteractionLogRepository.class);
    private final AiUsageStatsService service = new AiUsageStatsService(repository);

    private static AiInteractionLog row(String outcome, String phrase, String at) {
        return AiInteractionLog.builder().outcome(outcome).questionPhrase(phrase).createdAt(Instant.parse(at)).build();
    }

    @Test
    @DisplayName("counts outcomes, ignores rows with none, and groups repeated phrases")
    void aggregates() {
        when(repository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(
                row("MATCHED", null, "2026-11-01T10:00:00Z"),
                row("MATCHED", null, "2026-11-02T10:00:00Z"),
                row("AMBIGUOUS", "what is my", "2026-11-03T10:00:00Z"),
                row("NO_MATCH", "how do i view my payslip", "2026-11-04T10:00:00Z"),
                row("NO_MATCH", "how do i view my payslip", "2026-11-09T10:00:00Z"),
                row("NO_MATCH", "update bank details", "2026-11-05T10:00:00Z"),
                row(null, null, "2026-11-06T10:00:00Z"),   // provider outage: in no number
                row(null, null, "2026-11-07T10:00:00Z")));

        AiQueryInsightsResponse r = service.queryInsights(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-10"));

        assertThat(r.total()).isEqualTo(6);
        assertThat(r.matched()).isEqualTo(2);
        assertThat(r.ambiguous()).isEqualTo(1);
        assertThat(r.unmatched()).isEqualTo(3);
        assertThat(r.matchRate()).isEqualTo(2.0 / 6);
        assertThat(r.topUnmatched()).extracting(AiQueryInsightsResponse.UnmatchedPhrase::phrase)
                .containsExactly("how do i view my payslip", "update bank details"); // ambiguous not listed
        assertThat(r.topUnmatched().get(0).occurrences()).isEqualTo(2);
        assertThat(r.topUnmatched().get(0).lastAsked()).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
    }

    @Test
    @DisplayName("top list is capped at 10 and an empty range gives a zero match rate")
    void capAndEmpty() {
        when(repository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any())).thenReturn(
                java.util.stream.IntStream.range(0, 15)
                        .mapToObj(i -> row("NO_MATCH", "phrase " + i, "2026-11-01T10:00:00Z")).toList());
        assertThat(service.queryInsights(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")).topUnmatched())
                .hasSize(10);

        when(repository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());
        assertThat(service.queryInsights(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")).matchRate()).isZero();
    }

    @Test
    @DisplayName("an inverted or oversized range is rejected")
    void rangeValidation() {
        assertThatThrownBy(() -> service.queryInsights(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.queryInsights(LocalDate.parse("2024-01-01"), LocalDate.parse("2026-11-01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the endpoint requires the Super-Admin-only ai_assistant:VIEW:ALL authority")
    void endpointIsSuperAdminOnly() throws Exception {
        PreAuthorize guard = AiAssistantController.class
                .getMethod("queryInsights", LocalDate.class, LocalDate.class).getAnnotation(PreAuthorize.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("hasAuthority('ai_assistant:VIEW:ALL')");
    }
}

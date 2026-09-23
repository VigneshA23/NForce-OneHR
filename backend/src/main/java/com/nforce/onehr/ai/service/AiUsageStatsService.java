package com.nforce.onehr.ai.service;

import com.nforce.onehr.ai.dto.AiUsageBreakdownPoint;
import com.nforce.onehr.ai.dto.AiUsageDailyPoint;
import com.nforce.onehr.ai.dto.AiUsageStatsResponse;
import com.nforce.onehr.ai.entity.AiInteractionLog;
import com.nforce.onehr.ai.repository.AiInteractionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of the Super-Admin "API Usage" page (see {@link AiUsageStatsResponse}'s own javadoc
 * for what each field is for). Super-Admin-only is enforced at the controller, the same way
 * {@link AiRateLimitSettingsService#getForAdmin()} leaves it to its controller's
 * {@code @PreAuthorize} — a same-class call here would bypass a method-level annotation anyway.
 */
@Service
@RequiredArgsConstructor
public class AiUsageStatsService {

    /** Mirrors AuditLogController's own from/to convention, just windowed by a day count instead
     *  of two explicit timestamps — this page's charts are always "the last N days", never an
     *  arbitrary custom range, so a day count is the simpler param the frontend actually needs. */
    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int MAX_WINDOW_DAYS = 90;

    private final AiInteractionLogRepository interactionLogRepository;

    /** Plain mutable running total for one calendar day — a Lombok @Builder is immutable-on-build,
     *  which makes it the wrong shape for something accumulated into row by row. */
    private static final class DayBucket {
        final LocalDate date;
        // requestCount is real Mistral API-call attempts (embedding + completion, including
        // retries) summed across the day's turns, not a row count — see AiInteractionLog#
        // getApiCallAttempts()'s own javadoc for why a turn and a request are not the same thing.
        int requestCount, successCount, errorCount;
        long promptTokens, completionTokens, embeddingTokens;
        DayBucket(LocalDate date) { this.date = date; }
        AiUsageDailyPoint toPoint() {
            return AiUsageDailyPoint.builder().date(date).requestCount(requestCount).successCount(successCount)
                    .errorCount(errorCount).promptTokens(promptTokens).completionTokens(completionTokens)
                    .embeddingTokens(embeddingTokens).build();
        }
    }

    @Transactional(readOnly = true)
    public AiUsageStatsResponse stats(Integer requestedDays) {
        int days = requestedDays == null ? DEFAULT_WINDOW_DAYS
                : Math.min(Math.max(requestedDays, 1), MAX_WINDOW_DAYS);

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(days));

        List<AiInteractionLog> logs = interactionLogRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(from, to);

        // Seeded with every day in the window (oldest first), including days with zero traffic — a
        // chart with a real gap on it is exactly what "traceable, not opaque" requires; silently
        // skipping quiet days would hide the gap instead.
        Map<LocalDate, DayBucket> byDay = new LinkedHashMap<>();
        LocalDate fromDate = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = to.atZone(ZoneOffset.UTC).toLocalDate();
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            byDay.put(d, new DayBucket(d));
        }

        Map<String, Long> byErrorCode = new LinkedHashMap<>();
        Map<String, Long> byResponseType = new LinkedHashMap<>();
        long totalPromptTokens = 0, totalCompletionTokens = 0, totalEmbeddingTokens = 0, totalLatencyMs = 0;
        long totalApiCallAttempts = 0;
        int successCount = 0, errorCount = 0;

        for (AiInteractionLog log : logs) {
            LocalDate day = log.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            DayBucket bucket = byDay.get(day);

            int prompt = log.getPromptTokens() == null ? 0 : log.getPromptTokens();
            int completion = log.getCompletionTokens() == null ? 0 : log.getCompletionTokens();
            int embedding = log.getEmbeddingPromptTokens() == null ? 0 : log.getEmbeddingPromptTokens();
            totalPromptTokens += prompt;
            totalCompletionTokens += completion;
            totalEmbeddingTokens += embedding;
            totalApiCallAttempts += log.getApiCallAttempts();
            totalLatencyMs += log.getLatencyMs();

            if (log.isSuccess()) {
                successCount++;
                if (bucket != null) bucket.successCount++;
            } else {
                errorCount++;
                if (bucket != null) bucket.errorCount++;
                String code = log.getErrorCode() == null ? "UNKNOWN" : log.getErrorCode();
                byErrorCode.merge(code, 1L, Long::sum);
            }
            byResponseType.merge(log.getResponseType(), 1L, Long::sum);

            if (bucket != null) {
                bucket.requestCount += log.getApiCallAttempts();
                bucket.promptTokens += prompt;
                bucket.completionTokens += completion;
                bucket.embeddingTokens += embedding;
            }
        }

        List<AiUsageDailyPoint> daily = byDay.values().stream().map(DayBucket::toPoint).toList();

        return AiUsageStatsResponse.builder()
                .from(from)
                .to(to)
                .totalRequests(totalApiCallAttempts)
                .totalTurns(logs.size())
                .successCount(successCount)
                .errorCount(errorCount)
                .totalPromptTokens(totalPromptTokens)
                .totalCompletionTokens(totalCompletionTokens)
                .totalEmbeddingTokens(totalEmbeddingTokens)
                .totalTokens(totalPromptTokens + totalCompletionTokens + totalEmbeddingTokens)
                .avgLatencyMs(logs.isEmpty() ? 0.0 : (double) totalLatencyMs / logs.size())
                .daily(daily)
                .byErrorCode(toSortedBreakdown(byErrorCode))
                .byResponseType(toSortedBreakdown(byResponseType))
                .build();
    }

    private static List<AiUsageBreakdownPoint> toSortedBreakdown(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .map(e -> AiUsageBreakdownPoint.builder().key(e.getKey()).count(e.getValue()).build())
                .sorted(Comparator.comparingLong(AiUsageBreakdownPoint::getCount).reversed())
                .toList();
    }
}

package com.nforce.onehr.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the evaluation set against a live backend and prints a scorecard.
 *
 * <p><strong>This is not a unit test and must never run in the ordinary suite.</strong> Every
 * question costs a real embedding call plus a real completion call against a metered API, and the
 * answers are model output — the same question can pass and then fail without a line of code
 * changing. Asserting on it in CI would produce a build that fails for reasons nobody can fix.
 * Instead it reports, and a person reads the report.
 *
 * <p>It goes over HTTP rather than calling {@code AiAssistantService} directly, and logs in with
 * real credentials rather than constructing a context. That is the point: it exercises JWT
 * authentication, the role lookup, the audience filter and the navigation authorisation exactly as
 * a browser would, so "an Employee cannot retrieve admin knowledge" is proven end to end instead of
 * assumed from a mock.
 *
 * <pre>
 * AI_EVAL_BASE_URL=http://localhost:8081 \
 * AI_EVAL_EMPLOYEE=employee@example.com:password \
 * AI_EVAL_MANAGER=manager@example.com:password \
 * AI_EVAL_HR_ADMIN=hr@example.com:password \
 * AI_EVAL_SUPER_ADMIN=admin@example.com:password \
 *   mvn test -Dtest=AiEvaluationHarness -DfailIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Credentials come from the environment and are never written to a file, never logged, and never
 * included in the scorecard. Any role whose credentials are absent is skipped and reported as
 * skipped rather than passed.
 */
@Tag("ai-eval")
class AiEvaluationHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String baseUrl;

    @Test
    @DisplayName("evaluation scorecard (live; skipped without credentials)")
    void runEvaluationSet() throws Exception {
        baseUrl = env("AI_EVAL_BASE_URL", "http://localhost:8081");

        Map<String, String> tokens = new LinkedHashMap<>();
        for (String role : List.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN")) {
            String credentials = System.getenv("AI_EVAL_" + role);
            if (credentials == null || !credentials.contains(":")) continue;
            int split = credentials.indexOf(':');
            tokens.put(role, login(credentials.substring(0, split), credentials.substring(split + 1)));
        }

        // An Assumption, not a failure: nobody running `mvn test` asked to spend money, and a
        // red build for a missing optional environment variable trains people to ignore red builds.
        assumeTrue(!tokens.isEmpty(),
                "No AI_EVAL_<ROLE> credentials supplied; skipping the live evaluation set.");

        List<Result> results = new ArrayList<>();
        for (EvaluationSet.EvalQuestion question : EvaluationSet.load()) {
            String token = tokens.get(question.getRole());
            if (token == null) {
                results.add(Result.skipped(question));
                continue;
            }
            results.add(ask(question, token));
        }

        printScorecard(results);
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private String login(String email, String password) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("email", email, "password", password));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            // The email is named because the operator supplied it and needs to know which one
            // failed; the password is not echoed anywhere, including here.
            throw new IllegalStateException("Login failed for " + email + ": HTTP " + response.statusCode());
        }
        return MAPPER.readTree(response.body()).path("token").asText();
    }

    private Result ask(EvaluationSet.EvalQuestion question, String token) {
        try {
            String body = MAPPER.writeValueAsString(Map.of("message", question.getQuestion()));
            long started = System.nanoTime();
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/ai-assistant/chat"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            // Generous: a cold embedding plus a completion is genuinely slow, and a
                            // timeout counted as a wrong answer would misreport quality as accuracy.
                            .timeout(Duration.ofSeconds(90))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            long millis = (System.nanoTime() - started) / 1_000_000L;

            if (response.statusCode() != 200) {
                return Result.failed(question, millis, "HTTP " + response.statusCode());
            }
            return judge(question, MAPPER.readTree(response.body()), millis);
        } catch (Exception e) {
            return Result.failed(question, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ── Judging ─────────────────────────────────────────────────────────────

    /**
     * Checks one answer against its expectations.
     *
     * <p>Every check is mechanical — a type in a set, a substring, a pageId. Nothing here judges
     * whether prose is <em>good</em>, because a second model grading the first would add its own
     * failure mode to the thing being measured. What this catches is the failure that matters:
     * an answer that is fluent and wrong.
     */
    private Result judge(EvaluationSet.EvalQuestion question, JsonNode response, long millis) {
        List<String> failures = new ArrayList<>();

        String type = response.path("type").asText("");
        if (!question.getTypes().contains(type)) {
            failures.add("type was " + type + ", expected one of " + question.getTypes());
        }

        JsonNode navigation = response.path("navigation");
        String pageId = navigation.isMissingNode() || navigation.isNull()
                ? null : navigation.path("pageId").asText(null);

        if (question.forbidsNavigation() && pageId != null) {
            failures.add("offered navigation to '" + pageId + "' when none was allowed");
        } else if (question.requiresNavigation() && !question.getNavigation().equals(pageId)) {
            failures.add("navigation was " + pageId + ", expected " + question.getNavigation());
        }

        // Steps are folded in so a required phrase can live in the step list rather than the prose,
        // which is where a how-to answer naturally puts it.
        StringBuilder text = new StringBuilder(response.path("answer").asText(""));
        response.path("steps").forEach(step -> text.append('\n').append(step.asText()));
        String haystack = text.toString().toLowerCase(Locale.ROOT);

        for (String required : question.getMustMention()) {
            if (!haystack.contains(required.toLowerCase(Locale.ROOT))) {
                failures.add("did not mention '" + required + "'");
            }
        }
        if (!question.getMustMentionAny().isEmpty()
                && question.getMustMentionAny().stream()
                        .noneMatch(any -> haystack.contains(any.toLowerCase(Locale.ROOT)))) {
            failures.add("mentioned none of " + question.getMustMentionAny());
        }
        for (String forbidden : question.getMustNotMention()) {
            if (haystack.contains(forbidden.toLowerCase(Locale.ROOT))) {
                failures.add("mentioned '" + forbidden + "', which would be invented");
            }
        }

        return new Result(question, failures.isEmpty() ? Status.PASS : Status.FAIL, millis,
                String.join("; ", failures), response.path("answer").asText(""));
    }

    // ── Reporting ───────────────────────────────────────────────────────────

    private void printScorecard(List<Result> results) {
        long passed = results.stream().filter(r -> r.status == Status.PASS).count();
        long failed = results.stream().filter(r -> r.status == Status.FAIL).count();
        long skipped = results.stream().filter(r -> r.status == Status.SKIP).count();
        long asked = passed + failed;

        StringBuilder out = new StringBuilder("\n");
        out.append("═══ OneHR AI Assistant — evaluation scorecard ══════════════════════════\n\n");
        out.append(String.format("  %d asked · %d passed · %d failed · %d skipped%n",
                asked, passed, failed, skipped));
        if (asked > 0) {
            out.append(String.format("  pass rate %.0f%% · median %d ms%n", 100.0 * passed / asked, median(results)));
        }

        out.append("\n  BY CATEGORY\n");
        Map<String, int[]> byCategory = new TreeMap<>();
        for (Result r : results) {
            if (r.status == Status.SKIP) continue;
            int[] tally = byCategory.computeIfAbsent(r.question.getCategory(), key -> new int[2]);
            tally[r.status == Status.PASS ? 0 : 1]++;
        }
        byCategory.forEach((category, tally) -> out.append(String.format(
                "    %-16s %d/%d%n", category, tally[0], tally[0] + tally[1])));

        List<Result> failures = results.stream().filter(r -> r.status == Status.FAIL).toList();
        if (!failures.isEmpty()) {
            out.append("\n  FAILURES\n");
            for (Result r : failures) {
                out.append(String.format("    ✗ %-34s [%s]%n", r.question.getId(), r.question.getRole()));
                out.append("        asked : ").append(r.question.getQuestion()).append('\n');
                out.append("        why   : ").append(r.detail).append('\n');
                if (r.question.getWhy() != null) {
                    // Printed because a failure here is usually the specific regression the
                    // question was written to catch, and that context is what makes it actionable.
                    out.append("        guards: ").append(r.question.getWhy().trim().replace("\n", " ")).append('\n');
                }
                out.append("        got   : ").append(truncate(r.answer)).append("\n\n");
            }
        }

        if (skipped > 0) {
            out.append(String.format("%n  %d question(s) skipped for want of credentials.%n", skipped));
        }
        out.append("════════════════════════════════════════════════════════════════════════\n");

        // System.out rather than a logger: this is a report for a person watching a terminal, and
        // com.nforce.onehr logs at DEBUG everywhere, so it would be buried.
        System.out.println(out);
    }

    private long median(List<Result> results) {
        List<Long> timings = results.stream()
                .filter(r -> r.status != Status.SKIP)
                .map(r -> r.millis)
                .sorted(Comparator.naturalOrder())
                .toList();
        return timings.isEmpty() ? 0 : timings.get(timings.size() / 2);
    }

    private String truncate(String text) {
        String flat = text == null ? "" : text.replace("\n", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private enum Status { PASS, FAIL, SKIP }

    private record Result(EvaluationSet.EvalQuestion question, Status status,
                          long millis, String detail, String answer) {

        static Result skipped(EvaluationSet.EvalQuestion question) {
            return new Result(question, Status.SKIP, 0, "no credentials for " + question.getRole(), "");
        }

        static Result failed(EvaluationSet.EvalQuestion question, long millis, String detail) {
            return new Result(question, Status.FAIL, millis, detail, "");
        }
    }
}

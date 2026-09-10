package com.nforce.onehr.service;

import com.nforce.onehr.exception.EmployeeCodeConflictException;
import com.nforce.onehr.repository.EmployeeCodeSequenceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for NF-{@code YYYY}{@code NNNN} Employee IDs (e.g. {@code NF-20260007}).
 * Both {@link EmployeeService} and {@link UserManagementService} (including the legacy
 * {@code POST /api/employees} path) go through this instead of each keeping its own
 * MAX(employee_code)+1 logic.
 *
 * <p>The numeric suffix comes from one global Postgres sequence ({@code employee_code_seq},
 * V132) that never resets by year and never decreases — YYYY is simply the calendar year the ID
 * happens to be issued in, not part of the sequence's own numbering.
 *
 * <p>The Employee ID field on the Add Employee/User form is editable, and whatever the admin
 * submits — the untouched suggestion or a hand-typed value — is treated identically here: it is
 * the exact Employee ID to use. {@link #claim(String)} never substitutes a different value for
 * one that's already taken. A stale submission (e.g. two forms previewing the same ID, one of
 * which gets created first) must fail with a clear conflict rather than silently resolving to
 * the next sequence value — see {@link #claim(String)}'s own doc for why.
 *
 * <p>Every successful claim also keeps the sequence caught up: if the accepted code is shaped
 * like {@code NF-YYYYNNNN}, the sequence is bumped up to at least its numeric suffix (never
 * down — see {@link EmployeeCodeSequenceRepository#advanceAtLeastTo}), so a hand-typed ID that
 * jumps ahead of the current counter (e.g. skipping from {@code NF-20260025} to
 * {@code NF-20260050}) makes the next suggestion continue from {@code NF-20260051}, not silently
 * fall back to re-offering {@code NF-20260026}. A code that isn't shaped like that at all (e.g.
 * a fully custom string) can't be synced to, so the counter just ticks forward by one instead —
 * still enough to keep {@link #preview()} from re-suggesting the same slot forever.
 */
@Component
@RequiredArgsConstructor
public class EmployeeCodeGenerator {

    private static final String PREFIX = "NF";
    private static final int MIN_WIDTH = 4;

    /** Bounds preview's occupied-candidate walk (display only — never consumes the sequence). */
    private static final int MAX_ATTEMPTS = 20;

    /**
     * Matches exactly what {@link #format(long)} produces: {@code NF-} + a 4-digit year +
     * the zero-padded (or wider) sequence number. Used to pull the sequence number back out of
     * an accepted code so the sequence can be caught up to it — see {@link #claim(String)}.
     */
    private static final Pattern GENERATED_CODE_PATTERN =
            Pattern.compile("^" + PREFIX + "-\\d{4}(\\d{" + MIN_WIDTH + ",})$");

    private final EmployeeCodeSequenceRepository sequenceRepository;
    private final EmployeeRepository employeeRepository;

    /**
     * Read-only suggestion for the Employee ID field's initial value. Never consumes the
     * sequence — walks forward from the peeked base (pure arithmetic on the peeked value, still
     * zero {@code nextval()} calls) past any candidate that's already occupied, so the form
     * doesn't suggest an ID that's guaranteed to collide. Bounded by {@link #MAX_ATTEMPTS}; if
     * every candidate in that window is somehow occupied, falls back to the unadjusted peek.
     */
    public String preview() {
        long base = sequenceRepository.peekNextValue();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = format(base + attempt);
            if (!employeeRepository.existsByEmployeeCode(candidate)) {
                return candidate;
            }
        }
        return format(base);
    }

    /**
     * Resolves the Employee ID to persist for a new employee, called inside the
     * employee-creation transaction. The submitted value is validated as-is — this method never
     * swaps in a different Employee ID than the one it was asked for.
     *
     * <ul>
     *   <li>{@code requestedCode} blank/null — nothing was submitted (e.g. the legacy
     *       {@code POST /api/employees} path, or the preview never loaded). Atomically claims
     *       the next sequence value with a single {@code nextval()} call and uses it as-is.</li>
     *   <li>{@code requestedCode} present — whether it's the untouched suggestion or a
     *       hand-typed value, it is normalized and checked for availability. If it's already
     *       taken — including the race where another request claimed the exact same previewed
     *       ID first — this throws rather than silently trying the next sequence value; the
     *       caller must be told to retry, not handed an ID it never asked for. Once accepted,
     *       the sequence is caught up to it (see class doc) so the next {@link #preview()}
     *       doesn't re-suggest an already-used slot.</li>
     * </ul>
     *
     * @throws EmployeeCodeConflictException if {@code requestedCode} is already in use.
     */
    public String claim(String requestedCode) {
        if (requestedCode == null || requestedCode.isBlank()) {
            return format(sequenceRepository.nextValue());
        }
        String normalized = requestedCode.trim().toUpperCase();
        if (employeeRepository.existsByEmployeeCode(normalized)) {
            throw new EmployeeCodeConflictException(normalized);
        }
        Long sequenceValueInCode = parseSequenceValue(normalized);
        if (sequenceValueInCode != null) {
            // The admin's code (suggested or hand-typed) is itself sequence-shaped — e.g. they
            // jumped ahead from NF-20260025 to NF-20260050. Catch the sequence up to it so the
            // next preview continues from NF-20260051 instead of stranding it at NF-20260026.
            sequenceRepository.advanceAtLeastTo(sequenceValueInCode);
        } else {
            // Fully custom code (doesn't match NF-YYYYNNNN at all) — nothing to sync to, but
            // still tick the counter forward by one so it reflects one more employee created.
            sequenceRepository.nextValue();
        }
        return normalized;
    }

    private String format(long sequenceValue) {
        return String.format("%s-%d%0" + MIN_WIDTH + "d", PREFIX, Year.now().getValue(), sequenceValue);
    }

    /** Extracts the sequence number from a code shaped like {@link #format(long)} produces. */
    private Long parseSequenceValue(String normalizedCode) {
        Matcher matcher = GENERATED_CODE_PATTERN.matcher(normalizedCode);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

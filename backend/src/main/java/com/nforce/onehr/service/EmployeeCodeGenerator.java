package com.nforce.onehr.service;

import com.nforce.onehr.exception.EmployeeCodeConflictException;
import com.nforce.onehr.repository.EmployeeCodeSequenceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Year;

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
 */
@Component
@RequiredArgsConstructor
public class EmployeeCodeGenerator {

    private static final String PREFIX = "NF";
    private static final int MIN_WIDTH = 4;

    /** Bounds preview's occupied-candidate walk (display only — never consumes the sequence). */
    private static final int MAX_ATTEMPTS = 20;

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
     *       caller must be told to retry, not handed an ID it never asked for.</li>
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
        return normalized;
    }

    private String format(long sequenceValue) {
        return String.format("%s-%d%0" + MIN_WIDTH + "d", PREFIX, Year.now().getValue(), sequenceValue);
    }
}

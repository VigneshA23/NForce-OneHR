package com.nforce.onehr.service;

import com.nforce.onehr.exception.EmployeeCodeConflictException;
import com.nforce.onehr.repository.EmployeeCodeSequenceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Year;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the centralized Employee ID generator (format {@code NF-YYYYNNNN}, e.g.
 * {@code NF-20260007}).
 *
 * <p>{@code claim(requestedCode)} validates the submitted Employee ID exactly — it never
 * substitutes a different, freshly-generated code for one that's already taken. This is what
 * makes the two-tab race safe: if Tab A and Tab B both preview the same ID and both submit it
 * unedited, the second submission must fail with a clear conflict, not silently succeed with
 * the next sequence value. (An earlier iteration of this class auto-skipped to the next
 * sequence value on a blank/unedited submission — that behavior was removed because it
 * accepted a stale, already-claimed ID under the guise of "generating a new one.")
 *
 * <p>The real sequence is a Postgres feature (see V131) so EmployeeCodeSequenceRepository/
 * EmployeeRepository are mocked here to drive the generator's own logic in isolation — this
 * project has no Testcontainers/real-Postgres test infra set up yet, so the sequence's own
 * cross-transaction atomicity is a Postgres guarantee exercised at the database level rather
 * than re-proven here.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeCodeGeneratorTest {

    @Mock private EmployeeCodeSequenceRepository sequenceRepository;
    @Mock private EmployeeRepository employeeRepository;

    private EmployeeCodeGenerator generator() {
        return new EmployeeCodeGenerator(sequenceRepository, employeeRepository);
    }

    private String currentYear() {
        return String.valueOf(Year.now().getValue());
    }

    private String code(long seq) {
        return String.format("NF-%s%04d", currentYear(), seq);
    }

    // ─── preview() — read-only, never consumes the sequence ───────────────────────

    @Test
    void preview_formatsAsNfYearSequence_withSingleHyphenAfterPrefix_withoutConsumingTheSequence() {
        when(sequenceRepository.peekNextValue()).thenReturn(56L);

        String preview = generator().preview();

        assertEquals(code(56), preview);
        verify(sequenceRepository, never()).nextValue();
    }

    @Test
    void preview_calledTwice_returnsSameValue_whenSequenceNotAdvanced() {
        when(sequenceRepository.peekNextValue()).thenReturn(56L);

        EmployeeCodeGenerator generator = generator();
        String first = generator.preview();
        String second = generator.preview();

        assertEquals(first, second);
    }

    @Test
    void preview_baseCandidateOccupied_skipsToNextCandidate_withoutConsumingTheSequence() {
        when(sequenceRepository.peekNextValue()).thenReturn(6L);
        when(employeeRepository.existsByEmployeeCode(code(6))).thenReturn(true);
        when(employeeRepository.existsByEmployeeCode(code(7))).thenReturn(false);

        String preview = generator().preview();

        assertEquals(code(7), preview);
        verify(sequenceRepository, never()).nextValue();
    }

    @Test
    void preview_multipleOccupiedCandidates_skipsAllOfThem() {
        when(sequenceRepository.peekNextValue()).thenReturn(6L);
        when(employeeRepository.existsByEmployeeCode(code(6))).thenReturn(true);
        when(employeeRepository.existsByEmployeeCode(code(7))).thenReturn(true);
        when(employeeRepository.existsByEmployeeCode(code(8))).thenReturn(true);
        when(employeeRepository.existsByEmployeeCode(code(9))).thenReturn(false);

        assertEquals(code(9), generator().preview());
    }

    @Test
    void preview_everyCandidateInBoundOccupied_fallsBackToUnadjustedPeek_withoutConsumingTheSequence() {
        when(sequenceRepository.peekNextValue()).thenReturn(6L);
        when(employeeRepository.existsByEmployeeCode(any())).thenReturn(true);

        String preview = generator().preview();

        assertEquals(code(6), preview);
        verify(sequenceRepository, never()).nextValue();
        // 20 candidates checked (attempts 0..19), matching the bounded-loop contract.
        verify(employeeRepository, times(20)).existsByEmployeeCode(any());
    }

    // ─── claim(null/blank) — nothing submitted, single sequence draw, no existence check ───

    @Test
    void claim_withNoRequestedCode_returnsFormattedCodeFromSequence() {
        when(sequenceRepository.nextValue()).thenReturn(1L);

        String result = generator().claim(null);

        assertEquals(code(1), result);
    }

    @Test
    void claim_blankRequestedCode_behavesLikeNoRequestedCode() {
        when(sequenceRepository.nextValue()).thenReturn(7L);

        String result = generator().claim("   ");

        assertEquals(code(7), result);
    }

    @Test
    void claim_minimumFourDigitWidth_padsWithLeadingZeros() {
        when(sequenceRepository.nextValue()).thenReturn(5L);

        assertEquals(code(5), generator().claim(null));
    }

    @Test
    void claim_sequenceValueAbove9999_growsWidthInsteadOfWrappingOrTruncating() {
        when(sequenceRepository.nextValue()).thenReturn(10000L);

        assertEquals("NF-" + currentYear() + "10000", generator().claim(null));
    }

    @Test
    void claim_sequenceValueAbove99999_growsWidthAgain() {
        when(sequenceRepository.nextValue()).thenReturn(100000L);

        assertEquals("NF-" + currentYear() + "100000", generator().claim(null));
    }

    @Test
    void claim_noRequestedCode_callsSequenceExactlyOnce_neverLoopsOrChecksExistence() {
        when(sequenceRepository.nextValue()).thenReturn(6L);

        generator().claim(null);

        verify(sequenceRepository, times(1)).nextValue();
        verifyNoInteractions(employeeRepository);
    }

    // ─── claim(requestedCode) — the submitted ID is validated exactly, never substituted ───

    @Test
    void claim_requestedCodeNotInUse_returnsItNormalized_withoutConsumingTheSequence() {
        String suggested = code(56);
        when(employeeRepository.existsByEmployeeCode(suggested)).thenReturn(false);

        String result = generator().claim(suggested);

        assertEquals(suggested, result);
        verify(sequenceRepository, never()).nextValue();
    }

    @Test
    void claim_requestedCodeIsCaseInsensitiveAndTrimmed() {
        String normalized = code(56);
        when(employeeRepository.existsByEmployeeCode(normalized)).thenReturn(false);

        String result = generator().claim("  " + normalized.toLowerCase() + "  ");

        assertEquals(normalized, result);
    }

    @Test
    void claim_manuallyEditedRequestedCode_acceptedWhenAvailable() {
        // The admin replaced the suggested value entirely with their own choice.
        String custom = "NF-CUSTOM-001";
        when(employeeRepository.existsByEmployeeCode(custom)).thenReturn(false);

        assertEquals(custom, generator().claim(custom));
        verify(sequenceRepository, never()).nextValue();
    }

    @Test
    void claim_editedToAnotherSequenceShapedValue_stillValidatedExactly_notReassignedOnCollision() {
        // An edited value that still happens to look like NF-YYYYNNNN (e.g. the admin changed
        // 0007 to 0099) must be validated as-is — never silently reassigned to a different code.
        String edited = code(99);
        when(employeeRepository.existsByEmployeeCode(edited)).thenReturn(true);

        EmployeeCodeConflictException ex = assertThrows(EmployeeCodeConflictException.class,
                () -> generator().claim(edited));

        assertEquals(edited, ex.getRequestedCode());
        verify(sequenceRepository, never()).nextValue();
    }

    @Test
    void claim_requestedCodeAlreadyInUse_throwsConflictWithoutPersisting_andNeverConsumesTheSequence() {
        String duplicate = code(56);
        when(employeeRepository.existsByEmployeeCode(duplicate)).thenReturn(true);

        EmployeeCodeConflictException ex = assertThrows(EmployeeCodeConflictException.class,
                () -> generator().claim(duplicate));

        assertEquals(duplicate, ex.getRequestedCode());
        assertEquals("Employee ID is unavailable. Please go back and retry.", ex.getMessage());
        verify(sequenceRepository, never()).nextValue();
    }

    // ─── The reported two-tab race: same previewed ID submitted by both, unedited ───

    @Test
    void claim_sameSuggestedIdSubmittedTwice_firstSucceeds_secondRejectedWithoutAdvancingToNextId() {
        // Tab A and Tab B both preview NF-20260008 and both submit it unedited. Tab A's request
        // reaches claim() first while the code is still free; Tab B's reaches it after Tab A's
        // employee has already been persisted (existsByEmployeeCode now true for that exact
        // code). Tab B must be rejected outright — it must NOT fall back to nextValue() and
        // create NF-20260009.
        String previewedByBothTabs = code(8);
        when(employeeRepository.existsByEmployeeCode(previewedByBothTabs))
                .thenReturn(false)   // Tab A's check
                .thenReturn(true);   // Tab B's check, after Tab A has committed

        EmployeeCodeGenerator generator = generator();

        String tabAResult = generator.claim(previewedByBothTabs);
        assertEquals(previewedByBothTabs, tabAResult);

        EmployeeCodeConflictException tabBError = assertThrows(EmployeeCodeConflictException.class,
                () -> generator.claim(previewedByBothTabs));
        assertEquals(previewedByBothTabs, tabBError.getRequestedCode());
        assertEquals("Employee ID is unavailable. Please go back and retry.", tabBError.getMessage());

        // Neither call should have touched the sequence — a non-blank requestedCode never does.
        verify(sequenceRepository, never()).nextValue();
    }
}

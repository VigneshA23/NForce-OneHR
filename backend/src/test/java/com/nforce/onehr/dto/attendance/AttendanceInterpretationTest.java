package com.nforce.onehr.dto.attendance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Code-review corrective pass, finding 8: {@code effectiveIsLate}/{@code effectiveLateByMinutes}/
 * {@code effectiveShiftId} are the single, shared derivation AttendanceService/
 * WebClockInService/RegularizationService all now delegate to (rather than each re-deriving the
 * identical {@code noShift ? ... : ...} triplet inline) — this pins down that derivation directly
 * against the class itself, independent of any one caller.
 */
class AttendanceInterpretationTest {

    @Test
    void effective_forAResolvedOutcome_passesThroughTheRawFields() {
        AttendanceInterpretation resolved = AttendanceInterpretation.builder()
                .outcome(InterpretationOutcome.RESOLVED)
                .isLate(true)
                .lateByMinutes(15)
                .shiftId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .build();

        assertEquals(true, resolved.effectiveIsLate());
        assertEquals(15, resolved.effectiveLateByMinutes());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), resolved.effectiveShiftId());
    }

    @Test
    void effective_forAResolvedOutcome_notLate_passesThroughFalseAndZero() {
        AttendanceInterpretation resolved = AttendanceInterpretation.builder()
                .outcome(InterpretationOutcome.RESOLVED)
                .isLate(false)
                .lateByMinutes(0)
                .shiftId(UUID.randomUUID())
                .build();

        assertFalse(resolved.effectiveIsLate());
        assertEquals(0, resolved.effectiveLateByMinutes());
    }

    @Test
    void effective_forNoShiftAssigned_degradesAllThreeToTheirOrdinaryPresentDayValues() {
        AttendanceInterpretation noShift = AttendanceInterpretation.noShiftAssigned(LocalDate.of(2026, 1, 1));

        assertFalse(noShift.effectiveIsLate(), "never late with no Shift to be late against");
        assertEquals(0, noShift.effectiveLateByMinutes());
        assertNull(noShift.effectiveShiftId(), "never a fabricated Shift");
    }
}

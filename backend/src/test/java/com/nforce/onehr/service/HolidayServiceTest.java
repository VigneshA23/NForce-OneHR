package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateHolidayRequest;
import com.nforce.onehr.dto.HolidayResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Holiday;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests — see LeaveServiceTest for why this suite avoids
 * @SpringBootTest/H2 (citext-typed entities break H2 schema generation).
 *
 * Covers the "does an HR Admin edit/delete actually reach employees at that
 * location" question directly: updateHoliday/deleteHoliday mutate the same
 * row in place, and every read path (getHolidaysByLocation /
 * getHolidaysForMyLocation) re-queries live — there is no caching layer to
 * go stale.
 */
@ExtendWith(MockitoExtension.class)
class HolidayServiceTest {

    @Mock private HolidayRepository holidayRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private EmployeeRepository employeeRepository;

    private HolidayService holidayService;

    private final UUID holidayId = UUID.randomUUID();
    private final UUID hyderabadId = UUID.randomUUID();
    private final UUID bangaloreId = UUID.randomUUID();
    private Location hyderabad;
    private Location bangalore;

    @BeforeEach
    void setUp() {
        holidayService = new HolidayService(holidayRepository, locationRepository, employeeRepository);
        hyderabad = Location.builder().id(hyderabadId).name("Hyderabad").build();
        bangalore = Location.builder().id(bangaloreId).name("Bangalore Office").build();
    }

    @Test
    void createHoliday_savesAndReturnsResponse() {
        CreateHolidayRequest req = new CreateHolidayRequest();
        req.setHolidayName("  Diwali ");
        req.setHolidayDate(LocalDate.of(2026, 11, 8));
        req.setLocationId(hyderabadId);

        when(locationRepository.findById(hyderabadId)).thenReturn(Optional.of(hyderabad));
        when(holidayRepository.existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrue(any(), any(), any())).thenReturn(false);
        when(holidayRepository.save(any(Holiday.class))).thenAnswer(inv -> {
            Holiday h = inv.getArgument(0);
            h.setId(holidayId);
            return h;
        });

        HolidayResponse resp = holidayService.createHoliday(req);

        assertEquals("Diwali", resp.getHolidayName()); // trimmed
        assertEquals(hyderabadId.toString(), resp.getLocationId());
        assertTrue(resp.isActive());
    }

    @Test
    void createHoliday_duplicateActiveHoliday_throws() {
        CreateHolidayRequest req = new CreateHolidayRequest();
        req.setHolidayName("Diwali");
        req.setHolidayDate(LocalDate.of(2026, 11, 8));
        req.setLocationId(hyderabadId);

        when(locationRepository.findById(hyderabadId)).thenReturn(Optional.of(hyderabad));
        when(holidayRepository.existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrue(any(), any(), any())).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> holidayService.createHoliday(req));
        verify(holidayRepository, never()).save(any());
    }

    @Test
    void createHoliday_pastDate_throwsAndNeverSaves() {
        CreateHolidayRequest req = new CreateHolidayRequest();
        req.setHolidayName("Diwali");
        req.setHolidayDate(LocalDate.now().minusDays(1));
        req.setLocationId(hyderabadId);

        assertThrows(IllegalArgumentException.class, () -> holidayService.createHoliday(req));
        verify(locationRepository, never()).findById(any());
        verify(holidayRepository, never()).save(any());
    }

    @Test
    void createHoliday_todayIsAllowed() {
        CreateHolidayRequest req = new CreateHolidayRequest();
        req.setHolidayName("Diwali");
        req.setHolidayDate(LocalDate.now());
        req.setLocationId(hyderabadId);

        when(locationRepository.findById(hyderabadId)).thenReturn(Optional.of(hyderabad));
        when(holidayRepository.existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrue(any(), any(), any())).thenReturn(false);
        when(holidayRepository.save(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> holidayService.createHoliday(req));
    }

    @Test
    void updateHoliday_pastDateOnExistingHoliday_stillAllowed() {
        // Historical holidays (already-past dates) must stay editable — e.g. fixing
        // a typo in the name — so this restriction is deliberately create-only.
        Holiday existing = Holiday.builder().id(holidayId).holidayName("New Year's Day").holidayDate(LocalDate.of(2026, 1, 1)).location(hyderabad).active(true).build();

        CreateHolidayRequest req = new CreateHolidayRequest();
        req.setHolidayName("New Year's Day (corrected)");
        req.setHolidayDate(LocalDate.of(2026, 1, 1)); // still in the past relative to "now" in this suite
        req.setLocationId(hyderabadId);

        when(holidayRepository.findById(holidayId)).thenReturn(Optional.of(existing));
        when(locationRepository.findById(hyderabadId)).thenReturn(Optional.of(hyderabad));
        when(holidayRepository.existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrueAndIdNot(any(), any(), any(), any())).thenReturn(false);
        when(holidayRepository.save(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));

        HolidayResponse resp = assertDoesNotThrow(() -> holidayService.updateHoliday(holidayId, req));
        assertEquals("New Year's Day (corrected)", resp.getHolidayName());
    }

    @Test
    void updateHoliday_movesHolidayToNewLocation() {
        Holiday existing = Holiday.builder().id(holidayId).holidayName("Diwali").holidayDate(LocalDate.of(2026, 11, 8)).location(hyderabad).active(true).build();

        CreateHolidayRequest req = new CreateHolidayRequest();
        req.setHolidayName("Diwali");
        req.setHolidayDate(LocalDate.of(2026, 11, 8));
        req.setLocationId(bangaloreId); // moved from Hyderabad -> Bangalore

        when(holidayRepository.findById(holidayId)).thenReturn(Optional.of(existing));
        when(locationRepository.findById(bangaloreId)).thenReturn(Optional.of(bangalore));
        when(holidayRepository.existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrueAndIdNot(any(), any(), any(), any())).thenReturn(false);
        when(holidayRepository.save(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));

        HolidayResponse resp = holidayService.updateHoliday(holidayId, req);

        assertEquals(bangaloreId.toString(), resp.getLocationId());
        assertEquals("Bangalore Office", resp.getLocationName());
        // Same row mutated in place — an employee re-querying Hyderabad won't see it;
        // an employee re-querying Bangalore will. No stale copy is left behind.
        assertEquals(bangalore, existing.getLocation());
    }

    @Test
    void updateHoliday_duplicateExcludingSelf_throws() {
        Holiday existing = Holiday.builder().id(holidayId).holidayName("Diwali").holidayDate(LocalDate.of(2026, 11, 8)).location(hyderabad).active(true).build();

        CreateHolidayRequest req = new CreateHolidayRequest();
        req.setHolidayName("Diwali");
        req.setHolidayDate(LocalDate.of(2026, 11, 8));
        req.setLocationId(hyderabadId);

        when(holidayRepository.findById(holidayId)).thenReturn(Optional.of(existing));
        when(locationRepository.findById(hyderabadId)).thenReturn(Optional.of(hyderabad));
        when(holidayRepository.existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrueAndIdNot(any(), any(), any(), any())).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> holidayService.updateHoliday(holidayId, req));
        verify(holidayRepository, never()).save(any());
    }

    @Test
    void updateHoliday_notFound_throws() {
        when(holidayRepository.findById(holidayId)).thenReturn(Optional.empty());

        CreateHolidayRequest req = new CreateHolidayRequest();
        req.setHolidayName("Diwali");
        req.setHolidayDate(LocalDate.of(2026, 11, 8));
        req.setLocationId(hyderabadId);

        assertThrows(IllegalArgumentException.class, () -> holidayService.updateHoliday(holidayId, req));
    }

    @Test
    void deleteHoliday_softDeletesRatherThanRemoving() {
        Holiday existing = Holiday.builder().id(holidayId).holidayName("Diwali").holidayDate(LocalDate.of(2026, 11, 8)).location(hyderabad).active(true).build();
        when(holidayRepository.findById(holidayId)).thenReturn(Optional.of(existing));

        holidayService.deleteHoliday(holidayId);

        ArgumentCaptor<Holiday> saved = ArgumentCaptor.forClass(Holiday.class);
        verify(holidayRepository).save(saved.capture());
        assertFalse(saved.getValue().isActive());
        assertEquals(holidayId, saved.getValue().getId()); // same row, not a new one
    }

    @Test
    void deleteHoliday_notFound_throws() {
        when(holidayRepository.findById(holidayId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> holidayService.deleteHoliday(holidayId));
    }

    @Test
    void getHolidaysForMyLocation_afterEdit_reflectsCurrentRowState() {
        // Simulates: HR Admin edited the holiday's name; employee at that
        // location re-fetches (e.g. on page revisit) and gets the live row —
        // no separate cache to invalidate, no redeploy needed.
        Holiday updated = Holiday.builder().id(holidayId).holidayName("Diwali (Updated)").holidayDate(LocalDate.of(2026, 11, 9)).location(hyderabad).active(true).build();
        Employee employee = Employee.builder().location(hyderabad).build();

        when(employeeRepository.findByUser_Email("emp@test.com")).thenReturn(Optional.of(employee));
        when(holidayRepository.findByLocation_IdAndActiveTrue(hyderabadId)).thenReturn(List.of(updated));

        List<HolidayResponse> result = holidayService.getHolidaysForMyLocation("emp@test.com");

        assertEquals(1, result.size());
        assertEquals("Diwali (Updated)", result.get(0).getHolidayName());
    }

    @Test
    void getHolidaysForMyLocation_afterDelete_holidayDisappears() {
        Employee employee = Employee.builder().location(hyderabad).build();
        when(employeeRepository.findByUser_Email("emp@test.com")).thenReturn(Optional.of(employee));
        // The repository's own AndActiveTrue filter is what makes a deleted
        // (active=false) holiday vanish — simulated here by returning empty.
        when(holidayRepository.findByLocation_IdAndActiveTrue(hyderabadId)).thenReturn(List.of());

        List<HolidayResponse> result = holidayService.getHolidaysForMyLocation("emp@test.com");

        assertTrue(result.isEmpty());
    }

    @Test
    void getHolidaysForMyLocation_noLocationSet_returnsEmptyNotError() {
        Employee employee = Employee.builder().location(null).build();
        when(employeeRepository.findByUser_Email("emp@test.com")).thenReturn(Optional.of(employee));

        List<HolidayResponse> result = holidayService.getHolidaysForMyLocation("emp@test.com");

        assertTrue(result.isEmpty());
        verify(holidayRepository, never()).findByLocation_IdAndActiveTrue(any());
    }
}

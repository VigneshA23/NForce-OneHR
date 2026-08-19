package com.nforce.onehr.service;

import com.nforce.onehr.dto.org.CreateLocationRequest;
import com.nforce.onehr.dto.org.LocationResponse;
import com.nforce.onehr.dto.org.UpdateLocationRequest;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.DesignationRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Location name capitalization normalization on create/update, and preserved validation/duplicate behavior. */
@ExtendWith(MockitoExtension.class)
class OrgServiceLocationTest {

    @Mock private DepartmentRepository departmentRepo;
    @Mock private DesignationRepository designationRepo;
    @Mock private LocationRepository locationRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private EmployeeManagerHistoryRepository historyRepo;

    private OrgService service;

    @BeforeEach
    void setUp() {
        service = new OrgService(departmentRepo, designationRepo, locationRepo, employeeRepo, historyRepo);
        lenient().when(locationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepo.countByLocationId(any())).thenReturn(0L);
    }

    @ParameterizedTest
    @CsvSource({
            "hyderabad, Hyderabad",
            "HYDERABAD, Hyderabad",
            "hYdErAbAd, Hyderabad",
            "'new york', 'New York'",
            "'NEW YORK', 'New York'",
            "'nEW yORK', 'New York'",
            "Hyderabad, Hyderabad",
            "'hyderabad city office', 'Hyderabad City Office'"
    })
    void createLocation_normalizesNameCasing(String input, String expected) {
        CreateLocationRequest req = new CreateLocationRequest();
        req.setName(input);

        LocationResponse response = service.createLocation(req);

        assertEquals(expected, response.getName());
        ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
        verify(locationRepo).save(captor.capture());
        assertEquals(expected, captor.getValue().getName());
    }

    @Test
    void updateLocation_normalizesNameCasing() {
        UUID id = UUID.randomUUID();
        Location existing = Location.builder().id(id).name("Hyderabad").build();
        when(locationRepo.findById(id)).thenReturn(Optional.of(existing));

        UpdateLocationRequest req = new UpdateLocationRequest();
        req.setName("nEW yORK");

        LocationResponse response = service.updateLocation(id, req);

        assertEquals("New York", response.getName());
        assertEquals("New York", existing.getName());
    }

    @Test
    void createLocation_duplicateCheckIsCaseInsensitiveAgainstNormalizedName() {
        when(locationRepo.existsByNameIgnoreCase("New York")).thenReturn(true);

        CreateLocationRequest req = new CreateLocationRequest();
        req.setName("nEW yORK");

        assertThrows(IllegalArgumentException.class, () -> service.createLocation(req));
        verify(locationRepo, never()).save(any());
    }

    @Test
    void updateLocation_duplicateCheckIsCaseInsensitiveAgainstNormalizedName() {
        UUID id = UUID.randomUUID();
        Location existing = Location.builder().id(id).name("Hyderabad").build();
        when(locationRepo.findById(id)).thenReturn(Optional.of(existing));
        when(locationRepo.existsByNameIgnoreCase("New York")).thenReturn(true);

        UpdateLocationRequest req = new UpdateLocationRequest();
        req.setName("nEW yORK");

        assertThrows(IllegalArgumentException.class, () -> service.updateLocation(id, req));
        verify(locationRepo, never()).save(any());
    }

    @Test
    void updateLocation_allowsSavingSameNameRegardlessOfCasing() {
        UUID id = UUID.randomUUID();
        Location existing = Location.builder().id(id).name("Hyderabad").build();
        when(locationRepo.findById(id)).thenReturn(Optional.of(existing));

        UpdateLocationRequest req = new UpdateLocationRequest();
        req.setName("HYDERABAD");

        LocationResponse response = service.updateLocation(id, req);

        assertEquals("Hyderabad", response.getName());
        verify(locationRepo, never()).existsByNameIgnoreCase(anyString());
    }
}

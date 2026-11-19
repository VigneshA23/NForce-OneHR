package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreatePtRegisteredLocationRequest;
import com.nforce.onehr.dto.payroll.PtRegisteredLocationResponse;
import com.nforce.onehr.dto.payroll.UpdatePtRegisteredLocationRequest;
import com.nforce.onehr.entity.PtRegisteredLocation;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PtRegisteredLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PtRegisteredLocationServiceTest {

    @Mock private PtRegisteredLocationRepository ptRegisteredLocationRepository;
    @Mock private PayGroupRepository payGroupRepository;

    @InjectMocks private PtRegisteredLocationService service;

    private final UUID payGroupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(ptRegisteredLocationRepository.save(any(PtRegisteredLocation.class))).thenAnswer(inv -> {
            PtRegisteredLocation l = inv.getArgument(0);
            if (l.getId() == null) l.setId(UUID.randomUUID());
            return l;
        });
    }

    private CreatePtRegisteredLocationRequest createReq(String state, String location) {
        CreatePtRegisteredLocationRequest req = new CreatePtRegisteredLocationRequest();
        req.setStateName(state);
        req.setLocationName(location);
        return req;
    }

    @Test
    void create_unknownPayGroup_throwsNoSuchElement() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> service.create(payGroupId, createReq("Telangana", "Hyderabad")));
    }

    @Test
    void create_trimsAndPersists() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);

        PtRegisteredLocationResponse resp = service.create(payGroupId, createReq(" Telangana ", " Hyderabad "));

        assertEquals("Telangana", resp.getStateName());
        assertEquals("Hyderabad", resp.getLocationName());
        assertEquals(payGroupId, resp.getPayGroupId());
    }

    @Test
    void list_ordersByStateThenLocation() {
        PtRegisteredLocation a = PtRegisteredLocation.builder().id(UUID.randomUUID()).payGroupId(payGroupId).stateName("Telangana").locationName("Hyderabad").build();
        when(ptRegisteredLocationRepository.findByPayGroupIdOrderByStateNameAscLocationNameAsc(payGroupId)).thenReturn(List.of(a));

        List<PtRegisteredLocationResponse> result = service.list(payGroupId);

        assertEquals(1, result.size());
        assertEquals("Hyderabad", result.get(0).getLocationName());
    }

    @Test
    void update_unknownId_throwsNoSuchElement() {
        UUID missing = UUID.randomUUID();
        when(ptRegisteredLocationRepository.findById(missing)).thenReturn(Optional.empty());

        UpdatePtRegisteredLocationRequest req = new UpdatePtRegisteredLocationRequest();
        req.setStateName("Telangana");
        req.setLocationName("Hyderabad");

        assertThrows(NoSuchElementException.class, () -> service.update(missing, req));
    }

    @Test
    void delete_unknownId_throwsNoSuchElement() {
        UUID missing = UUID.randomUUID();
        when(ptRegisteredLocationRepository.existsById(missing)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> service.delete(missing));
        verify(ptRegisteredLocationRepository, never()).deleteById(any());
    }

    @Test
    void delete_existingId_deletes() {
        UUID id = UUID.randomUUID();
        when(ptRegisteredLocationRepository.existsById(id)).thenReturn(true);

        service.delete(id);

        verify(ptRegisteredLocationRepository).deleteById(id);
    }
}

package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayGroupFilingDetailsResponse;
import com.nforce.onehr.dto.payroll.UpdatePayGroupFilingDetailsRequest;
import com.nforce.onehr.entity.PayGroupFilingDetails;
import com.nforce.onehr.repository.PayGroupFilingDetailsRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PayGroupFilingDetailsServiceTest {

    @Mock private PayGroupFilingDetailsRepository payGroupFilingDetailsRepository;
    @Mock private PayGroupRepository payGroupRepository;

    @InjectMocks private PayGroupFilingDetailsService service;

    private final UUID payGroupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(payGroupFilingDetailsRepository.save(any(PayGroupFilingDetails.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void get_noRowYet_returnsEmptyDefaults() {
        when(payGroupFilingDetailsRepository.findById(payGroupId)).thenReturn(Optional.empty());

        PayGroupFilingDetailsResponse resp = service.get(payGroupId);

        assertEquals(payGroupId, resp.getPayGroupId());
        assertNull(resp.getPan());
    }

    @Test
    void update_unknownPayGroup_throwsNoSuchElement() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(false);

        assertThrows(java.util.NoSuchElementException.class,
                () -> service.update(payGroupId, new UpdatePayGroupFilingDetailsRequest()));
    }

    @Test
    void update_createsRowAndTrimsFields() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        when(payGroupFilingDetailsRepository.findById(payGroupId)).thenReturn(Optional.empty());

        UpdatePayGroupFilingDetailsRequest req = new UpdatePayGroupFilingDetailsRequest();
        req.setPan(" ABCDE1234F ");
        req.setForm130Signatory(" Rohit Shivramwar ");

        PayGroupFilingDetailsResponse resp = service.update(payGroupId, req);

        assertEquals("ABCDE1234F", resp.getPan());
        assertEquals("Rohit Shivramwar", resp.getForm130Signatory());
        assertEquals(payGroupId, resp.getPayGroupId());
    }

    @Test
    void update_existingRow_overwritesInPlace() {
        PayGroupFilingDetails existing = PayGroupFilingDetails.builder().payGroupId(payGroupId).pan("OLDPAN0000").build();
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        when(payGroupFilingDetailsRepository.findById(payGroupId)).thenReturn(Optional.of(existing));

        UpdatePayGroupFilingDetailsRequest req = new UpdatePayGroupFilingDetailsRequest();
        req.setPan("NEWPAN0000");

        PayGroupFilingDetailsResponse resp = service.update(payGroupId, req);

        assertEquals("NEWPAN0000", resp.getPan());
    }
}

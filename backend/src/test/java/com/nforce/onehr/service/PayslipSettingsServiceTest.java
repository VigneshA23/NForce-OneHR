package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayslipSettingsResponse;
import com.nforce.onehr.dto.payroll.UpdatePayslipSettingsRequest;
import com.nforce.onehr.entity.PayslipSettings;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PayslipSettingsServiceTest {

    @Mock private PayslipSettingsRepository payslipSettingsRepository;
    @Mock private PayGroupRepository payGroupRepository;

    @InjectMocks private PayslipSettingsService service;

    private final UUID payGroupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(payslipSettingsRepository.save(any(PayslipSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void get_noRowYet_returnsUnconfiguredDefaults() {
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.empty());

        PayslipSettingsResponse resp = service.get(payGroupId);

        assertEquals(payGroupId, resp.getPayGroupId());
        assertFalse(resp.isPasswordProtected());
        assertNull(resp.getDocumentHeaderText());
    }

    @Test
    void update_unknownPayGroup_throwsNoSuchElement() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> service.update(payGroupId, new UpdatePayslipSettingsRequest()));
    }

    @Test
    void update_createsRowAndTrimsText() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.empty());

        UpdatePayslipSettingsRequest req = new UpdatePayslipSettingsRequest();
        req.setPasswordProtected(true);
        req.setDocumentHeaderText(" PAYSLIP ");
        req.setFooterText(" Custom footer. ");

        PayslipSettingsResponse resp = service.update(payGroupId, req);

        assertTrue(resp.isPasswordProtected());
        assertEquals("PAYSLIP", resp.getDocumentHeaderText());
        assertEquals("Custom footer.", resp.getFooterText());
    }

    @Test
    void update_existingRow_overwritesInPlace() {
        PayslipSettings existing = PayslipSettings.builder().payGroupId(payGroupId).passwordProtected(true).build();
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.of(existing));

        UpdatePayslipSettingsRequest req = new UpdatePayslipSettingsRequest();
        req.setPasswordProtected(false);

        PayslipSettingsResponse resp = service.update(payGroupId, req);

        assertFalse(resp.isPasswordProtected());
    }
}

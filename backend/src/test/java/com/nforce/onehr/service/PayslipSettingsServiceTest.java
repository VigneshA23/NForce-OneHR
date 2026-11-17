package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayslipSettingsResponse;
import com.nforce.onehr.dto.payroll.UpdatePayslipSettingsRequest;
import com.nforce.onehr.entity.PayslipSettings;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipSettingsRepository;
import com.nforce.onehr.service.PayslipSettingsService.Logo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
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
        assertEquals("RIGHT", resp.getLogoAlignment());
        assertEquals(PayslipFields.DEFAULT_KEYS, resp.getPayslipFields());
    }

    // ── body/header customisation ──

    @Test
    void update_rejectsAnInvalidLogoAlignment() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);

        UpdatePayslipSettingsRequest req = new UpdatePayslipSettingsRequest();
        req.setLogoAlignment("CENTER");

        assertThrows(IllegalArgumentException.class, () -> service.update(payGroupId, req));
    }

    @Test
    void update_rejectsAnUnknownPayslipField() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.empty());

        UpdatePayslipSettingsRequest req = new UpdatePayslipSettingsRequest();
        req.setPayslipFields(List.of("dateOfJoining", "loanBalance"));

        assertThrows(IllegalArgumentException.class, () -> service.update(payGroupId, req));
    }

    @Test
    void update_savesBodyAndHeaderCustomisation() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.empty());

        UpdatePayslipSettingsRequest req = new UpdatePayslipSettingsRequest();
        req.setLogoAlignment("LEFT");
        req.setExcludeNaFields(true);
        req.setEarningsHeading(" Income ");
        req.setDeductionsHeading(" Outgoings ");
        req.setIncludeLeaveSummary(true);
        req.setIncludeArrearBreakup(true);
        req.setPayslipFields(List.of("uan", "pan", "uan"));

        PayslipSettingsResponse resp = service.update(payGroupId, req);

        assertEquals("LEFT", resp.getLogoAlignment());
        assertTrue(resp.isExcludeNaFields());
        assertEquals("Income", resp.getEarningsHeading());
        assertEquals("Outgoings", resp.getDeductionsHeading());
        assertTrue(resp.isIncludeLeaveSummary());
        assertTrue(resp.isIncludeArrearBreakup());
        assertEquals(List.of("uan", "pan"), resp.getPayslipFields());
    }

    @Test
    void update_emptyPayslipFields_resetsToTheDefaultSet() {
        PayslipSettings existing = PayslipSettings.builder().payGroupId(payGroupId).payslipFields("uan,pan").build();
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.of(existing));

        PayslipSettingsResponse resp = service.update(payGroupId, new UpdatePayslipSettingsRequest());

        assertEquals(PayslipFields.DEFAULT_KEYS, resp.getPayslipFields());
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

    // ── logo ──

    @Test
    void uploadLogo_unknownPayGroup_throwsNoSuchElement() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> service.uploadLogo(payGroupId,
                new MockMultipartFile("file", "logo.png", "image/png", new byte[] {1, 2, 3})));
    }

    @Test
    void uploadLogo_rejectsANonImageFile() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.uploadLogo(payGroupId,
                new MockMultipartFile("file", "logo.pdf", "application/pdf", new byte[] {1, 2, 3})));
    }

    @Test
    void uploadLogo_rejectsAFileOverTheSizeCap() {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.uploadLogo(payGroupId,
                new MockMultipartFile("file", "logo.png", "image/png", new byte[600 * 1024])));
    }

    @Test
    void uploadLogo_createsRowAndStoresBytes() throws Exception {
        when(payGroupRepository.existsById(payGroupId)).thenReturn(true);
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.empty());
        byte[] bytes = {1, 2, 3, 4};

        PayslipSettingsResponse resp = service.uploadLogo(payGroupId,
                new MockMultipartFile("file", "logo.png", "image/png", bytes));

        assertTrue(resp.isHasLogo());
    }

    @Test
    void removeLogo_unconfigured_throwsNoSuchElement() {
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.removeLogo(payGroupId));
    }

    @Test
    void removeLogo_clearsTheStoredBytes() {
        PayslipSettings existing = PayslipSettings.builder().payGroupId(payGroupId)
                .logo(new byte[] {1, 2, 3}).logoContentType("image/png").build();
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.of(existing));

        PayslipSettingsResponse resp = service.removeLogo(payGroupId);

        assertFalse(resp.isHasLogo());
    }

    @Test
    void getLogo_noneConfigured_returnsNull() {
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.empty());

        assertNull(service.getLogo(payGroupId));
    }

    @Test
    void getLogo_returnsBytesAndContentType() {
        PayslipSettings existing = PayslipSettings.builder().payGroupId(payGroupId)
                .logo(new byte[] {9, 9, 9}).logoContentType("image/png").build();
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.of(existing));

        Logo logo = service.getLogo(payGroupId);

        assertArrayEquals(new byte[] {9, 9, 9}, logo.data());
        assertEquals("image/png", logo.contentType());
    }
}

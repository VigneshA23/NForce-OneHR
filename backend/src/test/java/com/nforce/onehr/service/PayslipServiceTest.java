package com.nforce.onehr.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Designation;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PayGroup;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.entity.PayslipLineItem;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.entity.Arrears;
import com.nforce.onehr.entity.ArrearsSourceType;
import com.nforce.onehr.entity.ArrearsStatus;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.ArrearsRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.entity.PayslipSettings;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipLineItemRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.PayslipSettingsRepository;
import com.nforce.onehr.repository.SalaryComponentRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.PayslipService.PayslipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * US-C12: self-service payslip PDF. The access matrix is the point of this class — self, HR_ADMIN and
 * SUPER_ADMIN are allowed; a different employee and, explicitly, the employee's own MANAGER are
 * refused (403) before any payslip data is even looked up. Also: only APPROVED/DISBURSED runs are
 * ever queried, and employer-side line items never reach the PDF.
 */
@ExtendWith(MockitoExtension.class)
class PayslipServiceTest {

    @Mock private PayslipRepository payslipRepository;
    @Mock private PayslipLineItemRepository lineItemRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PayGroupRepository payGroupRepository;
    @Mock private UserRepository userRepository;
    @Mock private PayslipSettingsRepository payslipSettingsRepository;
    @Mock private SalaryComponentRepository salaryComponentRepository;
    @Mock private LeaveBalanceRepository leaveBalanceRepository;
    @Mock private ArrearsRepository arrearsRepository;
    @Mock private EmailService emailService;

    private PayslipService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID payGroupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PayslipService(payslipRepository, lineItemRepository, employeeRepository, payGroupRepository,
                userRepository, new PayslipAccessPolicy(), new PayslipPdfService(), payslipSettingsRepository,
                salaryComponentRepository, leaveBalanceRepository, arrearsRepository, emailService, 4);
        lenient().when(payslipSettingsRepository.findById(any())).thenReturn(Optional.empty());
    }

    // ── access matrix ──

    @Test
    void self_canDownloadOwnPayslip() throws IOException {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        stubPayslip();

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        assertEquals("application/pdf", file.contentType());
        assertEquals("payslip-EMP001-2026-11.pdf", file.fileName());
        assertEquals("%PDF", new String(file.data(), 0, 4));
        assertTrue(text(file.data()).contains("Asha Rao"));
    }

    @Test
    void hrAdmin_canDownloadAnotherEmployeesPayslip() {
        stubUser("hr@x.com", UUID.randomUUID(), "HR_ADMIN");
        stubPayslip();

        assertDoesNotThrow(() -> service.getPayslipPdf("hr@x.com", employeeId, "2026-11"));
    }

    @Test
    void sendPayslipEmail_rendersAndEmailsTheSamePdf_toTheEmployeesAddressOnFile() {
        stubUser("hr@x.com", UUID.randomUUID(), "HR_ADMIN");
        stubPayslip();
        Employee employee = employeeRepository.findById(employeeId).orElseThrow();
        User employeeUser = new User();
        employeeUser.setEmail("asha.rao@x.com");
        employee.setUser(employeeUser);
        when(emailService.sendPayslipEmail(any(), any(), any(), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));

        boolean sent = service.sendPayslipEmail("hr@x.com", employeeId, "2026-11");

        assertTrue(sent);
        ArgumentCaptor<byte[]> pdfCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(emailService).sendPayslipEmail(eq("asha.rao@x.com"), eq("Asha Rao"), eq("November 2026"),
                pdfCaptor.capture(), eq("payslip-EMP001-2026-11.pdf"));
        assertEquals("%PDF", new String(pdfCaptor.getValue(), 0, 4));
    }

    @Test
    void sendPayslipEmail_employeeHasNoEmailOnFile_throws() {
        stubUser("hr@x.com", UUID.randomUUID(), "HR_ADMIN");
        stubPayslip(); // Employee stubbed with no `user` set — no email address on file.

        assertThrows(IllegalStateException.class, () -> service.sendPayslipEmail("hr@x.com", employeeId, "2026-11"));
        verify(emailService, never()).sendPayslipEmail(any(), any(), any(), any(), any());
    }

    @Test
    void superAdmin_canDownloadAnotherEmployeesPayslip() {
        stubUser("root@x.com", UUID.randomUUID(), "SUPER_ADMIN");
        stubPayslip();

        assertDoesNotThrow(() -> service.getPayslipPdf("root@x.com", employeeId, "2026-11"));
    }

    @Test
    void differentEmployee_isRefused_beforeAnyPayslipLookup() {
        stubUser("peer@x.com", UUID.randomUUID(), "EMPLOYEE");

        assertThrows(AccessDeniedException.class, () -> service.getPayslipPdf("peer@x.com", employeeId, "2026-11"));

        verifyNoInteractions(payslipRepository, lineItemRepository, employeeRepository);
    }

    @Test
    void manager_ofTheEmployee_isRefused_evenForADirectReport() {
        // The employee's own line manager. Every other module would let this through; payslips must not.
        stubUser("manager@x.com", UUID.randomUUID(), "MANAGER", "EMPLOYEE");

        assertThrows(AccessDeniedException.class, () -> service.getPayslipPdf("manager@x.com", employeeId, "2026-11"));

        verifyNoInteractions(payslipRepository, lineItemRepository, employeeRepository);
    }

    // ── visibility and lookup ──

    @Test
    void onlyApprovedAndDisbursedRunsAreEverQueried() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        stubPayslip();

        service.getMyPayslipPdf("emp@x.com", "2026-11");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(payslipRepository).findReleasedForEmployeeBetween(eq(employeeId), any(), any(), statuses.capture());
        assertEquals(Set.of(PayrollRunStatus.APPROVED, PayrollRunStatus.DISBURSED), Set.copyOf(statuses.getValue()));
    }

    @Test
    void periodIsTheMonthOfTheCycleEnd() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        stubPayslip();

        service.getMyPayslipPdf("emp@x.com", "2026-11");

        verify(payslipRepository).findReleasedForEmployeeBetween(eq(employeeId),
                eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 30)), anyCollection());
    }

    @Test
    void noPayslipForThePeriod_isNotFound() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        when(payslipRepository.findReleasedForEmployeeBetween(eq(employeeId), any(), any(), anyCollection()))
                .thenReturn(List.of());

        assertThrows(NoSuchElementException.class, () -> service.getMyPayslipPdf("emp@x.com", "2026-11"));
    }

    @Test
    void self_cannotSeeOwnPayslip_beforeHrDistributesIt() {
        // The run is Approved (so findVisibleForEmployeeBetween — the admin-view path — would find
        // it), but HR hasn't run "Distribute Payslips" with Portal checked yet, so the self-service
        // released-gated query returns nothing.
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        when(payslipRepository.findReleasedForEmployeeBetween(eq(employeeId), any(), any(), anyCollection()))
                .thenReturn(List.of());

        assertThrows(NoSuchElementException.class, () -> service.getMyPayslipPdf("emp@x.com", "2026-11"));
        verifyNoInteractions(employeeRepository);
    }

    @Test
    void malformedPeriod_isRejected() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");

        assertThrows(IllegalArgumentException.class, () -> service.getMyPayslipPdf("emp@x.com", "2026-13"));
        assertThrows(IllegalArgumentException.class, () -> service.getMyPayslipPdf("emp@x.com", "November"));
        verifyNoInteractions(payslipRepository);
    }

    @Test
    void employerSideLineItems_neverReachThePdf() throws IOException {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        Payslip payslip = stubPayslip(
                item("Basic", SalaryComponentType.EARNING, "50000.00"),
                item("Employer PF", "CONTRIBUTION", "1800.00"),
                item("Professional Tax", SalaryComponentType.DEDUCTION, "200.00"));

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        String text = text(file.data());
        assertTrue(text.contains("Basic") && text.contains("Professional Tax"), text);
        assertFalse(text.contains("Employer PF"), text);
        assertNotNull(payslip);
    }

    @Test
    void componentMarkedNotShownOnPayslip_isDroppedFromTheRenderedPdf() throws IOException {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        PayslipLineItem basic = item("Basic", SalaryComponentType.EARNING, "50000.00");
        PayslipLineItem internalRecovery = item("Asset Damage Recovery", SalaryComponentType.DEDUCTION, "500.00");
        stubPayslip(basic, internalRecovery);
        when(salaryComponentRepository.findAllById(anyCollection())).thenReturn(List.of(
                component(basic.getComponentId(), true, 0),
                component(internalRecovery.getComponentId(), false, 0)));

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        String text = text(file.data());
        assertTrue(text.contains("Basic"), text);
        assertFalse(text.contains("Asset Damage Recovery"), text);
    }

    @Test
    void componentsPrint_inTheCatalogsDisplayOrder_notAlphabetically() throws IOException {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        PayslipLineItem basic = item("Basic", SalaryComponentType.EARNING, "50000.00");
        PayslipLineItem hra = item("House Rent Allowance", SalaryComponentType.EARNING, "20000.00");
        stubPayslip(basic, hra);
        when(salaryComponentRepository.findAllById(anyCollection())).thenReturn(List.of(
                component(basic.getComponentId(), true, 1),
                component(hra.getComponentId(), true, 0)));

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        String text = text(file.data());
        assertTrue(text.indexOf("House Rent Allowance") < text.indexOf("Basic"), text);
    }

    @Test
    void payslipSettings_customHeaderAndFooterText_reachTheRenderedPdf() throws IOException {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        stubPayslip();
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.of(
                PayslipSettings.builder().payGroupId(payGroupId).documentHeaderText("PAYSLIP").footerText("Custom footer.").build()));

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        String text = text(file.data());
        assertTrue(text.contains("PAYSLIP November 2026"), text);
        assertTrue(text.contains("Custom footer."), text);
    }

    @Test
    void payslipSettings_passwordProtected_encryptsWithTheEmployeesOwnPanUppercased() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        Payslip payslip = stubPayslip();
        Employee employee = employeeRepository.findById(employeeId).orElseThrow();
        employee.setPanNumber("abcde1234f");
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.of(
                PayslipSettings.builder().payGroupId(payGroupId).passwordProtected(true).build()));

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        assertThrows(Exception.class, () -> new PdfReader(file.data()));
        assertDoesNotThrow(() -> new PdfReader(file.data(), "ABCDE1234F".getBytes()));
        assertNotNull(payslip);
    }

    @Test
    void payslipSettings_includeLeaveSummary_pullsRealLeaveBalancesForTheCycleEndYear() throws IOException {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        stubPayslip();
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.of(
                PayslipSettings.builder().payGroupId(payGroupId).includeLeaveSummary(true).build()));
        LeaveType earned = LeaveType.builder().name("Earned Leave").build();
        LeaveBalance balance = LeaveBalance.builder().employeeUserId(employeeId).leaveType(earned)
                .totalDays(new BigDecimal("12")).usedDays(new BigDecimal("2")).build();
        when(leaveBalanceRepository.findByEmployeeUserIdAndYear(employeeId, 2026)).thenReturn(List.of(balance));

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        String text = text(file.data());
        assertTrue(text.contains("Leave Summary"), text);
        assertTrue(text.contains("Earned Leave"), text);
        assertTrue(text.contains("10"), text);
    }

    @Test
    void payslipSettings_leaveSummaryOff_neverCallsTheLeaveBalanceRepository() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        stubPayslip();
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.of(
                PayslipSettings.builder().payGroupId(payGroupId).includeLeaveSummary(false).build()));

        service.getMyPayslipPdf("emp@x.com", "2026-11");

        verifyNoInteractions(leaveBalanceRepository);
    }

    @Test
    void payslipSettings_includeArrearBreakup_pullsArrearsAppliedToThisRun() throws IOException {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        Payslip payslip = stubPayslip();
        when(payslipSettingsRepository.findById(payGroupId)).thenReturn(Optional.of(
                PayslipSettings.builder().payGroupId(payGroupId).includeArrearBreakup(true).build()));
        Arrears arrears = Arrears.builder().employeeId(employeeId).payGroupId(payGroupId)
                .amount(new BigDecimal("5000.00")).reason("Backdated salary revision")
                .sourceType(ArrearsSourceType.SALARY_REVISION).status(ArrearsStatus.APPLIED)
                .sourceCycleStart(LocalDate.of(2026, 9, 26)).sourceCycleEnd(LocalDate.of(2026, 10, 25))
                .appliedToRunId(payslip.getPayrollRun().getId()).createdBy(employeeId).build();
        when(arrearsRepository.findByEmployeeIdAndAppliedToRunId(eq(employeeId), any())).thenReturn(List.of(arrears));

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        String text = text(file.data());
        assertTrue(text.contains("Arrears Breakup"), text);
        assertTrue(text.contains("Backdated salary revision"), text);
        assertTrue(text.contains("5,000.00"), text);
    }

    @Test
    void listMyPeriods_isNewestFirstAndDistinct() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        when(payslipRepository.findReleasedForEmployee(eq(employeeId), anyCollection())).thenReturn(List.of(
                payslipEndingOn(LocalDate.of(2026, 11, 25)),
                payslipEndingOn(LocalDate.of(2026, 10, 25)),
                payslipEndingOn(LocalDate.of(2026, 10, 25))));

        assertEquals(List.of("2026-11", "2026-10"), service.listMyPeriods("emp@x.com"));
    }

    // ── helpers ──

    private void stubUser(String email, UUID id, String... roleCodes) {
        User user = new User();
        user.setId(id);
        Set<Role> roles = new java.util.HashSet<>();
        for (String code : roleCodes) {
            roles.add(Role.builder().code(code).build());
        }
        user.setRoles(roles);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    }

    private Payslip stubPayslip(PayslipLineItem... items) {
        Payslip payslip = payslipEndingOn(LocalDate.of(2026, 11, 25));
        // Stubbed for both the admin-view path (findVisibleForEmployeeBetween, ungated on released)
        // and the self-service path (findReleasedForEmployeeBetween) — lenient since any one test
        // only exercises one of the two, depending on whether the caller is viewing their own
        // payslip or someone else's (see PayslipService#renderForEmployee).
        lenient().when(payslipRepository.findVisibleForEmployeeBetween(eq(employeeId), any(), any(), anyCollection()))
                .thenReturn(List.of(payslip));
        lenient().when(payslipRepository.findReleasedForEmployeeBetween(eq(employeeId), any(), any(), anyCollection()))
                .thenReturn(List.of(payslip));
        Employee employee = new Employee();
        employee.setUserId(employeeId);
        employee.setEmployeeCode("EMP001");
        employee.setFullName("Asha Rao");
        employee.setJoiningDate(LocalDate.of(2024, 4, 1));
        employee.setDepartment(Department.builder().name("Engineering").build());
        employee.setDesignation(Designation.builder().title("Engineer").build());
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(payGroupRepository.findById(payGroupId)).thenReturn(Optional.of(
                PayGroup.builder().name("Default pay group").legalEntity("NForce One Pvt Ltd").currency("INR").build()));
        when(lineItemRepository.findByPayslipId(payslip.getId()))
                .thenReturn(items.length == 0 ? List.of(item("Basic", SalaryComponentType.EARNING, "100.00")) : List.of(items));
        return payslip;
    }

    private Payslip payslipEndingOn(LocalDate cycleEnd) {
        PayrollRun run = PayrollRun.builder().payGroupId(payGroupId).cycleStart(cycleEnd.minusDays(30))
                .cycleEnd(cycleEnd).status(PayrollRunStatus.APPROVED).build();
        return Payslip.builder().id(UUID.randomUUID()).payrollRun(run).employeeId(employeeId)
                .ctc(new BigDecimal("1200000")).cycleDays(31).employedDays(31).lopDays(BigDecimal.ZERO)
                .prorationFactor(BigDecimal.ONE).grossPay(new BigDecimal("100.00"))
                .totalDeductions(BigDecimal.ZERO).netPay(new BigDecimal("100.00")).build();
    }

    private PayslipLineItem item(String name, String type, String amount) {
        return PayslipLineItem.builder().componentId(UUID.randomUUID()).componentName(name)
                .componentType(type).amount(new BigDecimal(amount)).build();
    }

    private SalaryComponent component(UUID id, boolean shownOnPayslip, int displayOrder) {
        return SalaryComponent.builder().id(id).shownOnPayslip(shownOnPayslip).displayOrder(displayOrder).build();
    }

    private static String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            sb.append(extractor.getTextFromPage(p)).append('\n');
        }
        return sb.toString();
    }
}
